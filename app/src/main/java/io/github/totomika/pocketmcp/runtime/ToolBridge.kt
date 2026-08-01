package io.github.totomika.pocketmcp.runtime

import io.github.totomika.pocketmcp.data.log.LogManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具注册桥接: SDK addTool() ↔ JS handler。
 *
 * @param runtimeManager 查找 runtime
 * @param bridge QuickJs 桥接
 * @param logManager 日志管理器 (null 时不记录 MCP 调用日志)
 */
class ToolBridge(
    private val runtimeManager: RuntimeManager,
    private val bridge: QuickJsBridge = QuickJsBridge(),
    private val logManager: LogManager? = null,
) {

    /**
     * 调用指定工具的 handler。
     *
     * @param namespace 脚本 namespace
     * @param toolName 工具本地名
     * @param args 参数 JsonObject (来自 MCP SDK)
     * @return CallToolResult
     */
    suspend fun callHandler(
        namespace: String,
        toolName: String,
        args: JsonObject,
    ): CallToolResult {
        val runtime = runtimeManager.getRuntime(namespace)
            ?: return errorResult("Runtime not found for namespace: $namespace")

        // 中毒 runtime 直接拒绝, 避免排队等待永远不返回的调用
        if (runtime.poisoned) {
            logManager?.mcp(namespace, "Tool call rejected (runtime poisoned): ${toolName}")
            return errorResult("Runtime is unresponsive (previous call timed out). Restart the service to recover.")
        }

        logManager?.mcp(
            namespace,
            "→ tools/call ${namespace}_$toolName args=${args.toString().take(200)}"
        )

        // 单工具超时: 脚本可通过 mcp.tool(..., { timeoutMs }) 声明; 钳制到 [1s, 180s], 默认 30s。
        val toolDef = runtime.toolRegistry[toolName]
        val timeoutMs = clampToolTimeout(toolDef?.timeoutMs)

        // 并发上限信号量: callQueue 容量 8, 限制同一 runtime 同时在飞的工具调用数 (满则背压)。
        // 注意: 这里不串行化 JS —— 真正的"一次一个"由后面的单线程 dispatcher + evaluate 的
        // jsResultMutex 保证 (jsResultMutex 在 awaitAsyncJobs 期间也持有, 故等异步 I/O 时
        // 别的工具调用的 evaluate 进不来)。详见 docs/05-runtime.md。
        return try {
            runtime.callQueue.send(Unit) // 入队 (容量 8, 满则挂起)
            try {
                withTimeout(timeoutMs) {
                    val argsJson = args.toString()
                    val resultJson = bridge.callHandler(runtime, toolName, argsJson)
                    val result = parseResult(resultJson)
                    logManager?.mcp(
                        namespace,
                        "← result: ${
                            result.content.firstOrNull()?.let { (it as? TextContent)?.text }
                                ?.take(200) ?: "(empty)"
                        }"
                    )
                    result
                }
            } catch (e: TimeoutCancellationException) {
                // 超时不立即中毒: 先探针检测 dispatcher 线程是否真的卡死。
                //  - CPU 死循环 -> dispatcher 被占 -> 探针超时 -> 中毒 (runtime 确实救不回)。
                //  - I/O 慢 (fetch/文件) -> 取消后 dispatcher 空闲 (IO 在 Dispatchers.IO) -> 探针秒回 -> 不中毒,
                //    仅给该客户端返回超时, 其它调用不受影响。
                // 注意: 探针用 withContext(dispatcher){} 测线程可用性, **不走 evaluate**
                //       —— evaluate 的贪婪 awaitAsyncJobs 会 join 超时后仍未结束的 IO job, 把"I/O 慢"误判成"卡死"。
                val alive = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    withContext(runtime.dispatcher) { /* 仅测试 dispatcher 可用性, 不碰 JS */ }
                    true
                } == true
                if (!alive) {
                    runtime.poisoned = true
                    logManager?.mcp(
                        namespace,
                        "⚠ Tool timed out (${timeoutMs}ms) + dispatcher 卡死, runtime 中毒: ${namespace}_$toolName"
                    )
                    errorResult(
                        "Tool execution timed out (${timeoutMs}ms) and the runtime is unresponsive. " +
                            "It has been marked as poisoned; restart the service to recover."
                    )
                } else {
                    logManager?.mcp(
                        namespace,
                        "⚠ Tool timed out (${timeoutMs}ms), runtime 仍存活 (可能 I/O 慢): ${namespace}_$toolName"
                    )
                    errorResult(
                        "Tool execution timed out (${timeoutMs}ms). The runtime is still alive " +
                            "(likely slow I/O); you may retry."
                    )
                }
            } catch (e: Exception) {
                logManager?.mcp(namespace, "⚠ Tool error: ${e.message}")
                errorResult(e.message ?: "Unknown error")
            }
        } finally {
            runtime.callQueue.receive() // 出队
        }
    }

    /**
     * 解析 JS handler 返回的 JSON 为 CallToolResult。
     */
    private fun parseResult(json: String): CallToolResult {
        val obj = Json.parseToJsonElement(json) as JsonObject
        val content = obj["content"]?.jsonArray ?: JsonArray(emptyList())
        val isError = obj["isError"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

        val textContents = content.map { element ->
            val item = element as JsonObject
            TextContent(text = item["text"]?.jsonPrimitive?.content ?: "")
        }

        return CallToolResult(
            content = textContents,
            isError = isError,
        )
    }

    /**
     * 构造错误结果。
     */
    private fun errorResult(message: String) = CallToolResult(
        content = listOf(TextContent(text = message)),
        isError = true,
    )

    companion object {
        /** 默认工具调用超时: 30s */
        private const val DEFAULT_TIMEOUT_MS = 30_000L

        /** 单工具超时下限: 1s (防脚本误传过小值) */
        private const val MIN_TIMEOUT_MS = 1_000L

        /** 单工具超时上限: 180s (防脚本声明过长拖垮整个 runtime) */
        private const val MAX_TIMEOUT_MS = 180_000L

        /** 超时后探针 dispatcher 可用性的超时: 2s */
        private const val PROBE_TIMEOUT_MS = 2_000L

        /**
         * 钳制脚本声明的单工具超时: null/非正 -> 默认 30s; 否则夹到 [1s, 180s]。
         */
        private fun clampToolTimeout(requested: Long?): Long {
            if (requested == null || requested <= 0) return DEFAULT_TIMEOUT_MS
            return requested.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }
    }
}
