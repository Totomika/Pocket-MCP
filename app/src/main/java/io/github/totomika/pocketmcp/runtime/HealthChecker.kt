package io.github.totomika.pocketmcp.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 健康检查器。
 *
 * 每 30s 执行探针:
 * 1. evaluate("1") 带 2s 超时 — 检测 JS 是否卡死
 * 2. 检查 memoryUsage > 90% of 16MB
 *
 * 连续 3 次失败 → 通过 [onUnhealthy] 回调通知调用方 (当前接入实现是标记 runtime
 * 毒化, 阻止后续调用派往已损坏的引擎; 完整重建由 RuntimeManager 配合)。
 *
 * 注意: 探针经 [RuntimeEntry.runJs] 派发, 排在 dispatcher 队尾 —— 卡住的调用正好
 * 被探针检测到 (探针超时 = 线程不可用)。内存读取与 JS 同线程, 避免并发访问 native ctx。
 */
object HealthChecker {

    /** 探针间隔 */
    private const val PROBE_INTERVAL_MS = 30_000L

    /** 连续失败阈值 */
    private const val MAX_FAILURES = 3

    /** 内存使用率阈值 (90%) */
    private const val MEMORY_THRESHOLD_RATIO = 0.9

    /**
     * 运行健康检查循环, 直到协程被取消。
     *
     * 失败时通过 [onUnhealthy] 回调通知调用方 (由 RuntimeFactory 标记毒化,
     * 重建逻辑由 RuntimeManager 在下次 acquire 时接入)。
     */
    suspend fun run(
        entry: RuntimeEntry,
        onUnhealthy: (RuntimeEntry) -> Unit = {},
    ) {
        var consecutiveFailures = 0

        while (entry.quickJs.isClosed.not() &&
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true
        ) {
            delay(PROBE_INTERVAL_MS)

            val healthy = runProbe(entry)

            if (healthy) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_FAILURES) {
                    onUnhealthy(entry)
                    consecutiveFailures = 0 // 避免重复触发
                }
            }
        }
    }

    /**
     * 执行一次健康探针。
     *
     * 两个探针都在 [RuntimeEntry.runJs] 内完成: 探针排到 dispatcher 队尾,
     * 同时度量线程可用性与 JS 存活; 内存读取是 native 状态访问, 与 JS 同线程
     * 避免并发访问 native ctx。
     *
     * @return true 如果 runtime 健康
     */
    private suspend fun runProbe(entry: RuntimeEntry): Boolean {
        if (entry.quickJs.isClosed) return false
        return withTimeoutOrNull(RuntimePolicy.PROBE_TIMEOUT_MS) {
            entry.runJs {
                val evalOk = runCatching { evaluate<Int>("1") }.isSuccess
                if (!evalOk) {
                    false
                } else {
                    val limit = memoryLimit
                    !(!RuntimeFactory.isUnlimited(limit) &&
                        memoryUsage.memoryUsedSize > limit * MEMORY_THRESHOLD_RATIO)
                }
            }
        } ?: false
    }
}
