package io.github.totomika.pocketmcp

import com.dokar.quickjs.QuickJsException
import io.github.totomika.pocketmcp.runtime.PoisonReason
import io.github.totomika.pocketmcp.runtime.QuickJsBridge
import io.github.totomika.pocketmcp.runtime.RuntimeFactory
import io.github.totomika.pocketmcp.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P0-1 / P1-1 回归测试 (Android 设备/模拟器运行, QuickJs native lib 需要 Android)。
 *
 * 验证:
 * 1. 挂死的 runtime (纯 CPU 死循环工具占住 dispatcher) destroy() 快速返回 (孤儿化) — P0-1
 * 2. create() 失败 (语法错误) 后相同 ns 可重试成功 (无泄漏阻塞) — P1-1
 * 3. 中毒 runtime 被 acquire 重建不挂死 (完整链路) — P0-1
 *
 * 注意: 死循环工具会让 dispatcher 线程永久自旋 (quickjs-kt 无中断机制), 
 * 这些线程在测试进程结束时随进程回收, 属预期泄漏。
 */
class RuntimeHangAndLeakTest {

    /** P0-1: 死循环卡死 dispatcher 的 runtime, destroy() 必须快速返回 (孤儿化)。 */
    @Test
    fun destroy_of_hung_runtime_does_not_block() = runBlocking {
        val factory = RuntimeFactory()
        val entry = factory.create("hang-ns", HANG_SCRIPT)

        // 独立 scope 触发死循环工具, 让 dispatcher 真正卡死
        val hangScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callJob = hangScope.launch {
            try {
                QuickJsBridge().callHandler(entry, "loop", "{}")
            } catch (_: Exception) {
                // 死循环调用不会正常返回, 只可能在取消时抛异常
            }
        }
        // 等死循环跑起来 (evaluate 已进入 while(true), jsMutex 被持有)
        delay(500)
        // 标记中毒 (模拟 ToolBridge 超时 + 探针失败后的结论)
        entry.poison(PoisonReason.STUCK_DISPATCHER)

        val start = System.nanoTime()
        entry.destroy()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "destroy() 不应阻塞 (poisoned → 直接孤儿化), 实际 ${elapsedMs}ms",
            elapsedMs < 2_000,
        )

        // 清理: 取消挂起的调用 (无法中断死循环线程, 进程结束时回收)
        callJob.cancel()
        hangScope.cancel()
    }

    /** P1-1: 语法错误导致 create 失败后, 相同 ns 可重试成功 (前一次的 native/线程已清理)。 */
    @Test
    fun create_failure_then_retry_succeeds() = runBlocking {
        val factory = RuntimeFactory()
        try {
            factory.create("bad-ns", "mcp.tool(;;;")
            fail("语法错误脚本应抛 QuickJsException")
        } catch (e: QuickJsException) {
            // 预期
        }
        // 重试应成功 (前一次失败的 quickJs + dispatcher 已被清理, 不阻塞不冲突)
        val entry = factory.create("bad-ns", OK_SCRIPT)
        assertTrue("重试后工具应注册成功", entry.toolRegistry.containsKey("ok"))
        entry.destroy()
    }

    /** P0-1 完整链路: 中毒 runtime 被 acquire 重建, 不挂死, 且产生新 runtime。 */
    @Test
    fun poisoned_runtime_rebuild_does_not_hang() = runBlocking {
        val manager = RuntimeManager()
        val entry = manager.acquire("rebuild-ns", HANG_SCRIPT)

        // 触发死循环工具占住 dispatcher
        val hangScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callJob = hangScope.launch {
            try {
                QuickJsBridge().callHandler(entry, "loop", "{}")
            } catch (_: Exception) {
            }
        }
        delay(500)
        entry.poison(PoisonReason.STUCK_DISPATCHER)

        val start = System.nanoTime()
        // acquire 触发重建: destroy 旧 (孤儿化, 无 probe) + create 新
        val newEntry = manager.acquire("rebuild-ns", OK_SCRIPT)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("中毒重建不应阻塞, 实际 ${elapsedMs}ms", elapsedMs < 4_000)
        assertNotSame("重建应产生新 runtime", entry, newEntry)

        // 重建后 refCount = 旧引用(1) + 当前调用者新引用(1) = 2, 需 release 两次归零
        manager.release("rebuild-ns")
        manager.release("rebuild-ns")

        callJob.cancel()
        hangScope.cancel()
    }

    private companion object {
        val HANG_SCRIPT = """
            mcp.tool("loop", "infinite loop", {}, async () => { while(true) {} });
        """.trimIndent()

        val OK_SCRIPT = """
            mcp.tool("ok", "ok", {}, async () => ({ content: [{ type: "text", text: "ok" }] }));
        """.trimIndent()
    }
}
