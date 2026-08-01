package io.github.totomika.pocketmcp.runtime

import com.dokar.quickjs.MemoryUsage
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel

/**
 * 一个脚本运行时实例。
 *
 * 1 脚本 = 1 QuickJS runtime, 引用计数管理生命周期。
 * 多个 Profile 可以引用同一 runtime, 但只存在一份。
 *
 * @property namespace 脚本唯一标识
 * @property quickJs QuickJS 实例
 * @property dispatcher 单线程 dispatcher, 串行化所有 JS 调用
 * @property toolRegistry 已注册的工具 (本地名 → 工具定义)
 * @property scope 该 runtime 的协程作用域, 销毁时 cancel
 * @property callQueue 串行化队列 (FIFO, 深度 8), 排队 tools/call 请求
 * @property healthChecker 健康检查 Job (M2.5)
 */
data class RuntimeEntry(
    val namespace: String,
    val quickJs: QuickJs,
    val dispatcher: CoroutineDispatcher,
    val toolRegistry: MutableMap<String, ToolDefinition> = mutableMapOf(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    val callQueue: Channel<Unit> = Channel(capacity = QUEUE_DEPTH),
) {
    var healthChecker: Job? = null
    var refCount: Int = 0

    /**
     * 销毁前回调: 清理 host API 持有的资源 (DB handles 等)。
     * 由 RuntimeFactory 在创建 runtime 后设置。
     */
    var onDestroy: (() -> Unit)? = null

    /**
     * 中毒标记: 工具调用超时后标记为 true, 后续调用直接拒绝。
     *
     * 死循环脚本会卡住 dispatcher 线程, withTimeout 虽然取消协程,
     * 但 native JS 执行无法中断。标记中毒避免后续调用排队等待。
     */
    @Volatile
    var poisoned: Boolean = false

    /**
     * 当前内存使用情况。
     */
    val memoryUsage: MemoryUsage
        get() = quickJs.memoryUsage

    /**
     * 销毁 runtime: 停止所有后台 Job, 关闭 QuickJS, 释放线程。
     */
    fun destroy() {
        onDestroy?.invoke()
        healthChecker?.cancel()
        scope.cancel()
        callQueue.close()
        quickJs.close()
        // 关闭专用线程
        (dispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    companion object {
        /** 串行化队列深度 (docs/05-runtime.md: FIFO, 深度 8) */
        const val QUEUE_DEPTH = 8
    }
}

/**
 * 脚本通过 mcp.tool() 注册的工具定义。
 *
 * @property localName 工具本地名 (不含 namespace 前缀), 如 "read"
 * @property description 给 AI 看的工具说明
 * @property inputSchemaJson JSON Schema 字符串, 描述参数
 */
data class ToolDefinition(
    val localName: String,
    val description: String,
    val inputSchemaJson: String,
    /**
     * 该工具的单次调用超时 (ms), 由脚本通过 `mcp.tool(..., { timeoutMs })` 声明。
     * null = 用 [io.github.totomika.pocketmcp.runtime.ToolBridge] 默认值 (30s)。
     * 实际值会被 ToolBridge 钳制到 [1s, 180s]。
     */
    val timeoutMs: Long? = null,
)
