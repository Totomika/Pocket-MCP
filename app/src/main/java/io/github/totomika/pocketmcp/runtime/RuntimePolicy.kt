package io.github.totomika.pocketmcp.runtime

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime 层共享的策略常量。
 *
 * 此前 2s 探测超时在 ToolBridge / RuntimeEntry / HealthChecker 三处各有一份拷贝,
 * 语义相关却互不引用; 收敛到单一出处。
 */
internal object RuntimePolicy {
    /**
     * dispatcher 忙闲探测超时 (ms)。
     * ToolBridge 超时探针 / safeCloseQuickJs 销毁探测 / HealthChecker 健康探针共用。
     * 量级需容忍正常慢 I/O, 避免误判卡死。
     */
    const val PROBE_TIMEOUT_MS = 2_000L

    /**
     * create 阶段脚本顶层 evaluate 的硬超时 (ms)。
     * 防止脚本顶层死循环永久占住 RuntimeManager 的互斥锁 (P1-B)。
     */
    const val SCRIPT_EVALUATE_TIMEOUT_MS = 30_000L
}

/**
 * 孤儿化 runtime 的进程内累计账本。
 *
 * 孤儿化是"泄漏一条已卡死的线程 + 一个 native ctx, 换系统不瘫痪"的显式取舍;
 * 账本让该泄漏可观测 (累计计数随日志输出), 而不是静默发生。
 */
internal object OrphanLedger {
    private val orphans = AtomicLong(0)

    /** 记录一次孤儿化, 返回累计值。 */
    fun onOrphaned(namespace: String, reason: String): Long {
        val total = orphans.incrementAndGet()
        Log.w(
            "RuntimeEntry",
            "Runtime '$namespace' orphaned ($reason): leaked thread + native ctx, " +
                "reclaimed at process death. This is the price of an uninterruptible " +
                "JS infinite loop (quickjs-kt has no JS_SetInterruptHandler). " +
                "Total orphans so far: $total"
        )
        return total
    }

    /** 进程内累计孤儿化次数。 */
    fun total(): Long = orphans.get()
}