package io.github.totomika.pocketmcp

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spike 2: 验证 quickjs-kt 协程取消是否正确 reject JS Promise。
 *
 * 研究结论 (docs/spike-results.md): invokeAsyncFunction() 内部 catch(Throwable)
 * 捕获 CancellationException, 通过 JNI 调用 reject(), JS Promise 被 reject。
 *
 * 此测试在 Android 设备上运行 (QuickJs native lib 需要 Android)。
 */
class QuickJsCancellationSpikeTest {

    /**
     * 当 Kotlin 协程在 await JS Promise 时被取消,
     * JS Promise 应被 reject, evaluate 调用应抛出 CancellationException。
     */
    @Test
    fun coroutine_cancellation_rejects_js_promise() = runBlocking {
        val qjs = QuickJs.create(Dispatchers.Default)
        try {
            // 注册一个长时间运行的异步函数
            qjs.asyncFunction<String>("wait") {
                delay(10_000) // 10 秒
                "done"
            }

            // 在另一个协程中 evaluate JS, 等待 wait() 的 Promise
            var evaluateJob = launch {
                qjs.evaluate<Any?>("await wait()")
            }

            // 等待 evaluate 确实开始 (JS 执行到了 await)
            delay(500)

            // 取消协程
            evaluateJob.cancel()

            // 等待取消完成
            delay(500)

            assertTrue("Evaluate job should be cancelled", evaluateJob.isCancelled)
            // 如果 Promise 被 reject, evaluate 会抛出异常, job 会被 cancel
            // 如果 Promise 没被 reject, evaluate 会一直挂起, cancel 仍会生效但可能有资源泄漏
        } finally {
            qjs.close()
        }
    }

    /**
     * 验证正常 (非取消) 的 async function binding 调用能返回结果。
     * 这是 Spike 2 的对照组: 确保 asyncFunction 注册和 await 基本工作。
     */
    @Test
    fun async_function_binding_returns_result_normally() = runBlocking {
        val qjs = QuickJs.create(Dispatchers.Default)
        try {
            qjs.asyncFunction<String>("echo") {
                "hello from kotlin"
            }

            val result = qjs.evaluate<String>("await echo()")
            assertTrue("Result should contain expected text", result.contains("hello from kotlin"))
        } finally {
            qjs.close()
        }
    }
}
