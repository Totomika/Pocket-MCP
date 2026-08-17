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
 * 有意不用 data class: 可变状态 (refCount / onDestroy / 毒化) 声明在类体,
 * data class 的 copy() 只复制构造参数 —— 任何一次 copy 都会静默重置这些状态
 * (幽灵引用/丢失清理回调/丢失毒化标记); equals 语义也容易误导。
 * 全项目按引用身份使用本类, 无 equals/copy 需求。
 *
 * @property namespace 脚本唯一标识
 * @property quickJs QuickJS 实例
 * @property dispatcher runtime 专属单线程 dispatcher。它本身并不串行化任何 JS 调用
 *   —— JS 只会跑在这个线程上, 是因为所有入口都经由 [runJs] 派发 (quickjs-kt 的
 *   evaluate 拿 jsMutex 后在调用方线程执行 native JS, 单线程保证靠封装而非调度器)
 * @property toolRegistry 已注册的工具 (本地名 → 工具定义)
 * @property scope 该 runtime 的协程作用域, 销毁时 cancel
 * @property callQueue 并发上限队列 (FIFO, 深度 8), 限制同时 in-flight 的 tools/call
 * @property healthChecker 健康检查 Job
 */
class RuntimeEntry(
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
     * 中毒原因; null = 未中毒。
     *
     * 死循环脚本会卡住 dispatcher 线程, withTimeout 虽然取消协程,
     * 但 native JS 执行无法中断。标记中毒避免后续调用排队等待。
     */
    @Volatile
    private var _poisonReason: PoisonReason? = null

    /** 是否已中毒 (任何原因)。 */
    val poisoned: Boolean get() = poisonReason != null

    /** 中毒原因; null = 未中毒。 */
    val poisonReason: PoisonReason? get() = _poisonReason

    /**
     * 标记中毒。首个诊断者获胜 (最初的原因最可信, 后续来源不覆盖)。
     */
    @Synchronized
    fun poison(reason: PoisonReason) {
        if (_poisonReason == null) {
            _poisonReason = reason
        }
    }

    /**
     * 所有 JS 执行的唯一入口: 将 [block] 派发到 runtime 专属线程后执行。
     *
     * 不变量: 除本函数外, 任何代码不得直接调用 quickJs.evaluate。
     * 一旦 JS 在其它线程上运行, "dispatcher 空闲 ⟹ jsMutex 空闲"的探测前提被破坏,
     * [safeCloseQuickJs] 对 close() 的安全判断会失效 (自旋卡死)。
     * 新增 JS 入口时必须经由本函数, 这是封装层强制约束, 不靠调用点自律。
     */
    suspend fun <T> runJs(block: suspend QuickJs.() -> T): T =
        withContext(dispatcher) { quickJs.block() }

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
            // onDestroy (host API 资源清理) 失败仅记录, 不得中断销毁流程
            runCatching { onDestroy?.invoke() }.onFailure {
                Log.w("RuntimeEntry", "onDestroy cleanup failed for '$namespace'", it)
            }
            healthChecker?.cancel()
            scope.cancel()
            callQueue.close()
            if (poisonReason == PoisonReason.STUCK_DISPATCHER) {
                // 已由 ToolBridge 探针确认 dispatcher 被死循环占用, 无需再探测, 直接孤儿化
                OrphanLedger.onOrphaned(namespace, "stuck dispatcher (poisoned)")
            } else if (safeCloseQuickJs(quickJs, dispatcher)) {
                // 仅当 close 成功 (dispatcher 空闲) 才关闭线程池; 孤儿化时线程仍被死循环占用
                (dispatcher as? ExecutorCoroutineDispatcher)?.close()
            } else {
                OrphanLedger.onOrphaned(namespace, "dispatcher probe timeout")
            }
        }
    }

    companion object {
        /** 串行化队列深度 (FIFO, 深度 8) */
        const val QUEUE_DEPTH = 8
    }
}

/**
 * 毒化原因。此前用单一 Boolean 建模, "dispatcher 已确认卡死"与"引擎损坏但线程空闲"
 * 这类资源状态完全不同的情形共用同一处理 (一律孤儿化), 会白白泄漏空闲线程。
 */
enum class PoisonReason {
    /** 工具调用超时 + 探针确认 dispatcher 被死循环占用 (线程救不回, 销毁时直接孤儿化) */
    STUCK_DISPATCHER,
    /** QuickJS async 基础设施损坏 (死 promise / 引擎级异常), dispatcher 多半仍空闲 (销毁时先探测, 可正常回收线程) */
    BRIDGE_CORRUPTED,
    /** 健康检查连续失败 (探针超时或内存超限) */
    HEALTH_CHECK_FAILED,
}

/**
 * 安全关闭 QuickJs: 探测 dispatcher 空闲则正常 close, 忙 (死循环持锁) 则跳过走孤儿化。
 *
 * 说明: "dispatcher 空闲 ⟹ jsMutex 空闲"并非严格不变量 —— asyncFunction 的
 * resolve/reject 会在非 dispatcher 线程 (resumption 线程, 如 IO) 短暂获取 jsMutex
 * (µs 级 native resolve, 有界持有)。因此 dispatcher 空闲只保证 jsMutex 空闲或仅被
 * 有界持有, close() 的自旋等它无妨; 真正导致永久卡死的是 dispatcher 上运行的死循环
 * evaluate, 探测可捕获。该推理依赖 runJs 强制的"JS 只在 dispatcher 线程执行"不变量
 * —— 若有人在其它线程直接 evaluate 且死循环, 本函数会误判空闲并自旋。探测任务排在
 * dispatcher 队列尾部: 若线程被死循环占用, 探测挂起, withTimeoutOrNull 超时返回
 * null → 判定忙, 不碰 close()。
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
        withTimeoutOrNull(RuntimePolicy.PROBE_TIMEOUT_MS) {
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
