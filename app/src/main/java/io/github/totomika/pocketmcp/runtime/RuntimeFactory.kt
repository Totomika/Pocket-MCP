package io.github.totomika.pocketmcp.runtime

import com.dokar.quickjs.QuickJs
import io.github.totomika.pocketmcp.data.log.LogManager
import io.github.totomika.pocketmcp.script.RuntimeConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 创建 RuntimeEntry 的工厂。
 *
 * @param hostApiRegistry host.* API 注册表 (M3 注入, null 时只注入第 0 层)
 * @param logManager 日志管理器 (null 时不持久化日志)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeFactory(
    private val hostApiRegistry: io.github.totomika.pocketmcp.host.HostApiRegistry? = null,
    private val logManager: LogManager? = null,
) {

    /**
     * 创建并初始化一个 RuntimeEntry。
     *
     * 每个 runtime 拥有独立的单线程 dispatcher, 避免死循环脚本阻塞共享线程池。
     *
     * @param runtimeConfig 高级配置 (memoryLimit / maxStackSize), null = 用默认值
     * @throws com.dokar.quickjs.QuickJsException 脚本 evaluate 失败
     */
    suspend fun create(
        namespace: String,
        scriptCode: String,
        runtimeConfig: RuntimeConfig? = null,
    ): RuntimeEntry {
        val dispatcher = newSingleThreadContext("quickjs-$namespace")
        val quickJs = QuickJs.create(dispatcher)

        // 内存 + 栈限制: 优先用 runtimeConfig, null 字段回退到默认值
        // 注意: QuickJS 的 JS_SetMemoryLimit(ctx, 0) 是"0 字节可用"而非"无限制"!
        // RuntimeConfig 中 0 = 无限制, 需转为极大值传给 QuickJS
        quickJs.memoryLimit = runtimeConfig?.memoryLimit?.let { if (it == 0L) UNLIMITED else it } ?: DEFAULT_MEMORY_LIMIT
        quickJs.maxStackSize = runtimeConfig?.maxStackSize?.let { if (it == 0L) UNLIMITED else it } ?: DEFAULT_MAX_STACK_SIZE

        val entry = RuntimeEntry(
            namespace = namespace,
            quickJs = quickJs,
            dispatcher = dispatcher,
        )

        // 注入 mcp 对象
        injectMcpObject(quickJs, namespace)

        // 注入 host.* 第 0 层 API (console, timer, crypto)
        HostApiInjector.inject(quickJs, entry.scope, dispatcher, namespace, logManager)

        // 注入 host.* 第 1-4 层 API (kv/sql/fs/fetch/system, M3)
        hostApiRegistry?.injectAll(quickJs, namespace, entry.scope)

        // 注册销毁回调: 清理 host API 持有的资源
        entry.onDestroy = { hostApiRegistry?.cleanupAll(namespace) }

        // evaluate 脚本 (注册工具) — evaluate 是 suspend, 自动 drain pending jobs
        quickJs.evaluate<Any?>(scriptCode)

        // 提取已注册的工具
        extractTools(quickJs, entry.toolRegistry)

        return entry
    }

    /**
     * 启动后台 Job: 健康检查。
     *
     * 事件循环泵 (M2.4) 已移除: quickjs-kt 的 evaluate 内部 awaitAsyncJobs 是贪婪的,
     * 会自己 join 所有活跃 async job (含新调度的), 已构成事实上的事件循环。
     * 工具回调由 callHandler 的 evaluate 直接驱动, 无需外部泵 (见 EventLoopPumpSpikeTest)。
     * 已知限制: 顶层 setInterval 会让 evaluate 永不返回 (见 spike_a2)。
     */
    fun startBackgroundJobs(entry: RuntimeEntry) {
        // 健康检查 (M2.5): 每 30s 探针, 连续 3 次失败标记 poisoned
        // (完整重建逻辑需 RuntimeManager 配合, 目前至少阻止后续调用派往已损坏的引擎)
        entry.healthChecker = entry.scope.launch {
            HealthChecker.run(entry) { unhealthy ->
                unhealthy.poisoned = true
            }
        }
    }

    companion object {
        /** 默认内存上限: 16 MiB。 */
        const val DEFAULT_MEMORY_LIMIT = 16L * 1024 * 1024

        /** 默认栈上限: 512 KiB。 */
        const val DEFAULT_MAX_STACK_SIZE = 512L * 1024

        /** "无限制"的实际值。QuickJS 的 0 = "0 字节可用", 需用极大值表示无限制。 */
        const val UNLIMITED = Long.MAX_VALUE

        /** 判断 memoryLimit / maxStackSize 是否表示"无限制"。 */
        fun isUnlimited(limit: Long): Boolean = limit == UNLIMITED

        /**
         * 注入 mcp 对象。
         *
         * mcp = { namespace, _tools: [], tool(name, desc, schema, handler, options?) }
         * tool name 校验: ^[a-zA-Z0-9_-]+$, 禁止含点。
         * options.timeoutMs: 单工具调用超时 (ms), 可选; ToolBridge 钳制到 [1s, 180s]。
         */
        private fun injectMcpObject(quickJs: QuickJs, namespace: String) {
            val code = """
                const mcp = {
                  namespace: "$namespace",
                  _tools: [],
                  tool(name, description, inputSchema, handler, options) {
                    if (!/^[a-zA-Z0-9_-]+$/.test(name)) {
                      throw new Error("Tool name must match ^[a-zA-Z0-9_-]+$: " + name);
                    }
                    const timeoutMs = (options && typeof options.timeoutMs === "number") ? options.timeoutMs : null;
                    this._tools.push({ name, description, inputSchema, handler, timeoutMs });
                  }
                };
            """.trimIndent()
            // injectMcpObject 在 evaluate 脚本前调用, 用 runBlocking 保证顺序
            kotlinx.coroutines.runBlocking { quickJs.evaluate<Any?>(code) }
        }

        /**
         * 从 mcp._tools 提取工具定义到 registry。
         */
        private suspend fun extractTools(
            quickJs: QuickJs,
            registry: MutableMap<String, ToolDefinition>
        ) {
            val code = """
                JSON.stringify(mcp._tools.map(t => ({
                  name: t.name,
                  description: t.description,
                  inputSchema: t.inputSchema,
                  timeoutMs: t.timeoutMs
                })))
            """.trimIndent()
            val json = quickJs.evaluate<String>(code)
            val toolsArray = Json.parseToJsonElement(json) as JsonArray
            for (element in toolsArray) {
                val obj = element as JsonObject
                val name = obj["name"]!!.jsonPrimitive.content
                val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                val schema = obj["inputSchema"]?.toString() ?: "{}"
                val timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                registry[name] = ToolDefinition(
                    localName = name,
                    description = desc,
                    inputSchemaJson = schema,
                    timeoutMs = timeoutMs,
                )
            }
        }
    }
}
