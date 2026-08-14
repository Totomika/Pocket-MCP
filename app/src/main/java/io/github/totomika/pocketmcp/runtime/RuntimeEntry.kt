package io.github.totomika.pocketmcp.runtime

import android.util.Log
import com.dokar.quickjs.MemoryUsage
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.RejectedExecutionException

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
     *
     * **永不阻塞调用方**: 若 dispatcher 线程被死循环 JS 占用 (jsMutex 被持锁),
     * quickJs.close() 会自旋等待锁而永久卡死 (库实现为无 yield 的 tight-loop withLockSync,
     * 见 quickjs-kt Mutex.ext.kt)。此时跳过 close 走"孤儿化": 泄漏 1 线程 + 1 native ctx
     * (死循环中毒前已占用该核心, 不新增系统级阻塞; 进程死亡时全部回收),
     * 换取调用方 (RuntimeManager.mutex 持有者 / Main 线程) 快速返回, 系统不死。
     *
     * 清理全程 NonCancellable: 若调用方协程在 stop 中途被取消 (如 viewModelScope 销毁),
     * 默认上下文下第一个挂起点会直接抛 CancellationException 跳过清理 → 泄漏。
     *
     * 注意: 禁止在本 runtime 自己的 dispatcher 线程上调用本方法 (探测会自死锁)。
     */
    suspend fun destroy() {
        withContext(NonCancellable) {
            onDestroy?.invoke()
            healthChecker?.cancel()
            scope.cancel()
            callQueue.close()
            if (poisoned) {
                // 中毒已由 ToolBridge 的 2s 探针确认 dispatcher 卡死, 无需再探测, 直接孤儿化
                orphanLog("poisoned")
            } else if (safeCloseQuickJs(quickJs, dispatcher)) {
                // 仅当 close 成功 (dispatcher 空闲) 才关闭线程池; 孤儿化时线程仍被死循环占用
                (dispatcher as? ExecutorCoroutineDispatcher)?.close()
            } else {
                orphanLog("dispatcher probe timeout")
            }
        }
    }

    /** 孤儿化时打警告日志 (泄漏的是死循环线程, 进程死亡时回收)。 */
    private fun orphanLog(reason: String) {
        Log.w(
            "RuntimeEntry",
            "Runtime '$namespace' orphaned ($reason): leaked thread + native ctx, " +
                "reclaimed at process death. This is the price of an uninterruptible " +
                "JS infinite loop (quickjs-kt has no JS_SetInterruptHandler)."
        )
    }

    companion object {
        /** 串行化队列深度 (docs/05-runtime.md: FIFO, 深度 8) */
        const val QUEUE_DEPTH = 8
    }
}

/**
 * dispatcher 忙闲探测超时 (ms)。
 *
 * 中毒重建/销毁路径上, 探测会阻塞调用方 (含 RuntimeManager.mutex) 最多这么久。
 * 2s 与 ToolBridge 的探针超时 (PROBE_TIMEOUT_MS) 量级一致, 避免正常慢 I/O 误判。
 */
internal const val DISPATCHER_PROBE_TIMEOUT_MS = 2_000L

/**
 * 安全关闭 QuickJs: 探测 dispatcher 空闲则正常 close, 忙 (死循环持锁) 则跳过走孤儿化。
 *
 * 说明: "dispatcher 空闲 ⟹ jsMutex 空闲"并非严格不变量 —— asyncFunction 的
 * resolve/reject 会在非 dispatcher 线程 (resumption 线程, 如 IO) 短暂获取 jsMutex
 * (µs 级 native resolve, 有界持有)。因此 dispatcher 空闲只保证 jsMutex 空闲或仅被
 * 有界持有, close() 的自旋等它无妨; 真正导致永久卡死的持有者永远是 dispatcher 上
 * 运行的死循环 evaluate, 探测可捕获。探测任务排在 dispatcher 队列尾部: 若线程被
 * 死循环占用, 探测挂起, withTimeoutOrNull 超时返回 null → 判定忙, 不碰 close()。
 *
 * @param quickJs 可能为 null (create 早期失败), null 视为无需关闭
 * @return true = 已完整关闭; false = 孤儿化 (native 资源随进程消亡, 调用方不被阻塞)
 */
internal suspend fun safeCloseQuickJs(
    quickJs: QuickJs?,
    dispatcher: CoroutineDispatcher,
): Boolean {
    if (quickJs == null || quickJs.isClosed) return true
    val idle = try {
        withTimeoutOrNull(DISPATCHER_PROBE_TIMEOUT_MS) {
            withContext(dispatcher) { true }
        } == true
    } catch (e: RejectedExecutionException) {
        // dispatcher 已关闭 (重复销毁), 视为已清理
        true
    }
    if (idle) {
        quickJs.close()
        return true
    }
    return false
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
