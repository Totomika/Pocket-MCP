package io.github.totomika.pocketmcp

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spike 1: 验证 quickjs-kt 的失控保护机制。
 *
 * 研究结论 (docs/spike-results.md): interruptHandler (JS_SetInterruptHandler) 不可用。
 * 退化方案: memoryLimit + maxStackSize 作为防线。
 *
 * 这些测试在 Android 设备上运行 (QuickJs native lib 需要 Android)。
 */
class QuickJsInterruptSpikeTest {

    /**
     * Spike 1a: memoryLimit 能杀死持续分配内存的死循环。
     *
     * 纯 CPU 死循环 (while(true){}) 不分配内存, memoryLimit 不会触发。
     * 但配合内存分配的循环会被 memoryLimit 杀死, 抛出 QuickJsException。
     */
    @Test
    fun memoryLimit_kills_memory_allocating_loop() = runBlocking {
        val qjs = QuickJs.create(Dispatchers.Default)
        qjs.memoryLimit = 1024 * 1024 // 1 MB
        try {
            try {
                qjs.evaluate<Any?>(
                    "var arr = []; while(true) { arr.push('x'.repeat(1024)); }"
                )
                fail("Should have thrown QuickJsException (OOM)")
            } catch (e: QuickJsException) {
                // Expected: memory limit exceeded
            }
        } finally {
            qjs.close()
        }
    }

    /**
     * Spike 1b: maxStackSize 能杀死无限递归。
     *
     * 防止 native crash, 抛出 QuickJsException (stack overflow)。
     */
    @Test
    fun maxStackSize_kills_deep_recursion() = runBlocking {
        val qjs = QuickJs.create(Dispatchers.Default)
        qjs.maxStackSize = 64 * 1024 // 64 KB
        try {
            try {
                qjs.evaluate<Any?>("function f() { f() }; f()")
                fail("Should have thrown QuickJsException (stack overflow)")
            } catch (e: QuickJsException) {
                // Expected: stack overflow
            }
        } finally {
            qjs.close()
        }
    }

    /**
     * Spike 1c: 纯 CPU 死循环无法被 withTimeout 中断。
     *
     * 证明 interruptHandler 不可用: evaluate("while(true){}") 会永久阻塞,
     * withTimeout 取消协程后, evaluate 抛出 CancellationException,
     * 但 JS 引擎内部可能仍在执行 (无法真正中断 JS)。
     *
     * 此测试验证: withTimeout 超时后, 调用方确实收到超时 (CancellationException),
     * 但不保证 JS 已停止执行 — 这是已知限制。
     *
     * 附加验证 (修正旧注释的错误结论): 死循环 runtime 的 close() 会**永久卡死** —
     * 库的 close() 用 jsMutex.withLockSync (无 yield 的 tight-loop 自旋) 等锁,
     * 而死循环 evaluate 正持有 jsMutex (evalAndAwait 的 jsMutex.withLock { evalBlock() })。
     * 因此这里故意不调用 close(), 让测试进程结束时回收 native 资源。
     * 生产代码 (RuntimeEntry.destroy) 通过探测 dispatcher 判断 close 或孤儿化,
     * 绝不在死循环 runtime 上直接 close (见 docs 与 RuntimeHangAndLeakTest)。
     */
    @Test
    fun pure_infinite_loop_cannot_be_interrupted() = runBlocking {
        val qjs = QuickJs.create(Dispatchers.Default)
        var cancelled = false
        try {
            withTimeout(2000) {
                qjs.evaluate<Any?>("while(true) {}")
            }
            fail("Should have timed out")
        } catch (e: Exception) {
            // Expected: TimeoutCancellationException or CancellationException
            cancelled = true
        }
        assertTrue("withTimeout should have cancelled the evaluate call", cancelled)
        // NOTE: 不调用 qjs.close() — 死循环仍持有 jsMutex, close() 会永久自旋卡死
        // (旧注释 "close() will forcefully terminate the native runtime" 是错误结论)。
        // 死循环线程随测试进程结束被回收。
    }
}
