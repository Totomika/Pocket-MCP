package io.github.totomika.pocketmcp

import com.dokar.quickjs.QuickJs
import io.github.totomika.pocketmcp.runtime.HostApiInjector
import io.github.totomika.pocketmcp.runtime.QuickJsBridge
import io.github.totomika.pocketmcp.runtime.RuntimeEntry
import io.github.totomika.pocketmcp.runtime.RuntimeFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timer 机制验证 (Kotlin 驱动版)。
 *
 * host.setTimeout/setInterval 现在走 Kotlin 驱动: 注册 (sync binding) 立即返回,
 * 等待/重复在 Kotlin 协程, 到点用独立 evaluate 注入执行回调。
 *
 * 这组测试验证新行为:
 *  - 顶层 setInterval/setTimeout 不挂死脚本加载、不阻塞 evaluate
 *  - 回调确实会延后触发
 *  - 工具里的 setTimeout 不阻塞工具返回, 回调延后跑
 *  - clearInterval 能停止重复
 *
 * 与旧 EventLoopPumpSpikeTest 的区别: 旧的断言"贪婪 awaitAsyncJobs 阻塞/挂死",
 * 新实现把"重复"移出 JS 异步 job, 那些旧行为不再成立, 故整套替换。
 *
 * 在 Android 设备/模拟器上运行 (QuickJs native lib 需要 Android)。
 * JS 执行统一经 [RuntimeEntry.runJs] (与主代码不变量一致)。
 */
class TimerMechanicsTest {

    /** 构造仅含第 0 层 host API 的独立 runtime (不走 RuntimeFactory, 保持测试最小化)。 */
    private suspend fun createBareEntry(namespace: String): RuntimeEntry {
        val dispatcher = newSingleThreadContext(namespace)
        val quickJs = QuickJs.create(dispatcher)
        val entry = RuntimeEntry(namespace = namespace, quickJs = quickJs, dispatcher = dispatcher)
        HostApiInjector.inject(entry, namespace, null)
        return entry
    }

    /**
     * 顶层 setInterval: 注册即返回 (不挂死加载), 之后按间隔重复触发。
     */
    @Test
    fun setInterval_top_level_does_not_hang_and_fires() = runBlocking {
        val entry = createBareEntry("tl1")
        try {
            val start = System.nanoTime()
            entry.runJs { evaluate<Any?>("var count = 0; host.setInterval(() => { count++; }, 50);") }
            val loadElapsed = (System.nanoTime() - start) / 1_000_000L
            assertTrue(
                "顶层 setInterval 注册应立即返回, 不阻塞加载 (实际 ${loadElapsed}ms)",
                loadElapsed < 200L,
            )

            delay(130) // ~2 次 tick (50ms, 100ms)
            val count = entry.runJs { evaluate<String>("String(count)") }.toLong()
            assertTrue("130ms 内 interval 应至少触发 2 次, 实际 $count", count >= 2L)
        } finally {
            entry.destroy()
        }
    }

    /**
     * 顶层 setTimeout: 注册即返回 (不挂死加载); 到点后回调触发一次。
     */
    @Test
    fun setTimeout_top_level_does_not_block_load_and_fires_once() = runBlocking {
        val entry = createBareEntry("tl2")
        try {
            val start = System.nanoTime()
            entry.runJs {
                evaluate<Any?>(
                    "globalThis.__fired = false; host.setTimeout(() => { globalThis.__fired = true; }, 200);"
                )
            }
            val loadElapsed = (System.nanoTime() - start) / 1_000_000L
            assertTrue("顶层 setTimeout 注册应立即返回 (实际 ${loadElapsed}ms)", loadElapsed < 100L)

            val before = entry.runJs { evaluate<String>("String(globalThis.__fired)") }
            assertTrue("200ms 未到, 回调不应已触发: $before", before.contains("false"))

            delay(250)
            val after = entry.runJs { evaluate<String>("String(globalThis.__fired)") }
            assertTrue("250ms 后回调应已触发: $after", after.contains("true"))
        } finally {
            entry.destroy()
        }
    }

    /**
     * 工具 handler 里 setTimeout: 工具立即返回 (不等 timer), 回调延后触发。
     *
     * 对比旧实现: 旧实现工具会被 callHandler 的 evaluate 贪婪 join 阻塞 ~200ms 才返回。
     */
    @Test
    fun tool_setTimeout_returns_immediately_callback_fires_later() = runBlocking {
        val factory = RuntimeFactory()
        val script = """
            mcp.tool("fire", "fire a 200ms timer then return", {}, async () => {
                host.setTimeout(() => { globalThis.__fired = true; }, 200);
                return { content: [{ type: "text", text: "ok" }] };
            });
        """.trimIndent()

        val entry = factory.create("tl3", script, null)
        try {
            val bridge = QuickJsBridge()
            val start = System.nanoTime()
            val res = bridge.callHandler(entry, "fire", "{}")
            val elapsed = (System.nanoTime() - start) / 1_000_000L
            assertTrue(
                "工具应立即返回 (不阻塞等 timer), 实际 ${elapsed}ms, res=$res",
                elapsed < 100L && res.contains("ok"),
            )

            val before = entry.runJs { evaluate<String>("String(globalThis.__fired === true)") }
            assertTrue("工具刚返回, 回调不应已触发: $before", before.contains("false"))

            delay(250)
            val after = entry.runJs { evaluate<String>("String(globalThis.__fired === true)") }
            assertTrue("250ms 后回调应已触发: $after", after.contains("true"))
        } finally {
            entry.destroy()
        }
    }

    /**
     * clearInterval 能停止重复触发。
     */
    @Test
    fun clearInterval_stops_repeating() = runBlocking {
        val entry = createBareEntry("tl4")
        try {
            // interval 50ms, 120ms 后用 setTimeout 清掉
            entry.runJs {
                evaluate<Any?>(
                    "var count = 0; " +
                        "const id = host.setInterval(() => { count++; }, 50); " +
                        "host.setTimeout(() => host.clearInterval(id), 120);"
                )
            }
            delay(300)
            val c1 = entry.runJs { evaluate<String>("String(count)") }.toLong()
            delay(300)
            val c2 = entry.runJs { evaluate<String>("String(count)") }.toLong()
            assertTrue("clear 前应至少触发 2 次: $c1", c1 >= 2L)
            assertTrue("clearInterval 后 count 不应继续增长 (c1=$c1, c2=$c2)", c1 == c2)
        } finally {
            entry.destroy()
        }
    }
}
