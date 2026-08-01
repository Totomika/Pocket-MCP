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
 * 连续 3 次失败 → 销毁 runtime, 重建 (通知调用方)。
 *
 * 注意: evaluate 被 jsMutex 保护, 探针会排在 in-flight call 后面。
 * 这是特性: 卡住的调用正好被探针检测到 (docs/05-runtime.md)。
 */
object HealthChecker {

    /** 探针间隔 */
    private const val PROBE_INTERVAL_MS = 30_000L

    /** 单次探针超时 */
    private const val PROBE_TIMEOUT_MS = 2_000L

    /** 连续失败阈值 */
    private const val MAX_FAILURES = 3

    /** 内存使用率阈值 (90%) */
    private const val MEMORY_THRESHOLD_RATIO = 0.9

    /**
     * 运行健康检查循环, 直到协程被取消。
     *
     * 失败时通过 [onUnhealthy] 回调通知调用方重建 runtime。
     * 当前实现: 仅记录 (M2.5 骨架), 重建逻辑由 RuntimeManager 接入。
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
     * @return true 如果 runtime 健康
     */
    private suspend fun runProbe(entry: RuntimeEntry): Boolean {
        if (entry.quickJs.isClosed) return false

        // 探针 1: evaluate("1") 带 2s 超时
        val evalOk = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            try {
                entry.quickJs.evaluate<Int>("1")
                true
            } catch (e: Exception) {
                false
            }
        } ?: false

        if (!evalOk) return false

        // 探针 2: 内存使用检查
        val usage = entry.memoryUsage
        val memoryUsed = usage.memoryUsedSize
        val memoryLimit = entry.quickJs.memoryLimit
        // UNLIMITED (Long.MAX_VALUE) = 无限制, 跳过检查
        if (!RuntimeFactory.isUnlimited(memoryLimit) && memoryUsed > memoryLimit * MEMORY_THRESHOLD_RATIO) {
            return false
        }

        return true
    }
}
