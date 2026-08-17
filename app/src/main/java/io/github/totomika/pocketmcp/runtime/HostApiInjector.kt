package io.github.totomika.pocketmcp.runtime

import com.dokar.quickjs.binding.function
import io.github.totomika.pocketmcp.data.log.LogLevel
import io.github.totomika.pocketmcp.data.log.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 注入 host.* 第 0 层 API: console, timer, crypto。
 *
 * 这些 API 不需要权限检查, 所有脚本均可使用 (第 0 层)。
 *
 * Timer 实现: Kotlin 驱动 —— 注册走 sync function binding (不产生 JS async job),
 * "等待/重复"在 Kotlin 协程里完成, 到点用独立 evaluate 注入执行回调。
 * 这样 evaluate 的贪婪 awaitAsyncJobs 不会去 join 未来 tick, 顶层 setInterval/setTimeout
 * 不挂死脚本加载、不阻塞工具调用。定时器协程跑在 runtime 单线程 dispatcher 上,
 * 与工具调用共享 JS 单线程 (best-effort 触发)。详见 TimerMechanicsTest。
 *
 * 注入期的 evaluate 一律经 [RuntimeEntry.runJs] 派发到 runtime 专属线程执行;
 * function binding 注册是 define 操作, 不执行 JS, 直接用 [RuntimeEntry.quickJs]。
 */
object HostApiInjector {

    /**
     * 注入所有第 0 层 host API。
     *
     * @param entry 目标 runtime
     * @param namespace 脚本 namespace
     * @param logManager 日志管理器 (null 时不持久化日志)
     */
    suspend fun inject(
        entry: RuntimeEntry,
        namespace: String,
        logManager: LogManager? = null,
    ) {
        injectConsole(entry, namespace, logManager)
        injectTimers(entry, namespace)
        CryptoHost.inject(entry)
    }

    /**
     * host.console.log/info/warn/error
     *
     * 写入日志持久化 (Room) + println。
     */
    private suspend fun injectConsole(
        entry: RuntimeEntry,
        namespace: String,
        logManager: LogManager?,
    ) {
        val console = ConsoleBridge(entry.scope, namespace, logManager)

        entry.quickJs.function<Any?>("__console_log") { args ->
            console.log("info", args.joinToString(" ") { it?.toString() ?: "null" })
            null
        }
        entry.quickJs.function<Any?>("__console_info") { args ->
            console.log("info", args.joinToString(" ") { it?.toString() ?: "null" })
            null
        }
        entry.quickJs.function<Any?>("__console_warn") { args ->
            console.log("warn", args.joinToString(" ") { it?.toString() ?: "null" })
            null
        }
        entry.quickJs.function<Any?>("__console_error") { args ->
            console.log("error", args.joinToString(" ") { it?.toString() ?: "null" })
            null
        }

        entry.runJs {
            evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.console = {
                  log: (...a) => __console_log(a),
                  info: (...a) => __console_info(a),
                  warn: (...a) => __console_warn(a),
                  error: (...a) => __console_error(a),
                };
            """.trimIndent()
            )
        }
    }

    /**
     * host.setTimeout / clearTimeout / setInterval / clearInterval
     *
     * Kotlin 驱动: 注册 (sync binding) 立即返回; 等待与重复在 Kotlin 协程;
     * 到点用独立 evaluate 注入执行 JS 回调。回调以 id 存于 globalThis.__tl[id]。
     */
    private suspend fun injectTimers(
        entry: RuntimeEntry,
        namespace: String,
    ) {
        val timers = TimerRegistry(entry, namespace)

        entry.quickJs.function<Long>("__tl_next_id") { timers.nextId() }

        entry.quickJs.function<Any?>("__tl_schedule") { args ->
            val id = (args.firstOrNull() as? Number)?.toLong() ?: 0L
            val millis = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
            val repeat = (args.getOrNull(2) as? Boolean) ?: false
            timers.schedule(id, millis, repeat)
            null
        }

        entry.quickJs.function<Any?>("__tl_cancel") { args ->
            val id = (args.firstOrNull() as? Number)?.toLong()
            if (id != null) timers.cancel(id)
            null
        }

        entry.runJs {
            evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                if (!globalThis.__tl) { globalThis.__tl = {}; }

                host.setTimeout = function(callback, timeout) {
                    const id = __tl_next_id();
                    globalThis.__tl[id] = callback;
                    __tl_schedule(id, timeout || 0, false);
                    return id;
                };

                host.clearTimeout = function(id) {
                    __tl_cancel(id);
                    delete globalThis.__tl[id];
                };

                host.setInterval = function(callback, interval) {
                    const id = __tl_next_id();
                    globalThis.__tl[id] = callback;
                    __tl_schedule(id, interval || 0, true);
                    return id;
                };

                host.clearInterval = function(id) {
                    __tl_cancel(id);
                    delete globalThis.__tl[id];
                };
                """.trimIndent()
            )
        }
    }
}

/**
 * Console 日志桥接。
 *
 * 写入 Room 日志持久化 + println。
 */
private class ConsoleBridge(
    private val scope: CoroutineScope,
    private val namespace: String,
    private val logManager: LogManager?,
) {
    fun log(level: String, message: String) {
        println("[host.console.$level] [$namespace] $message")
        logManager?.console(namespace, LogLevel.valueOf(level.uppercase()), message)
    }
}

/**
 * Timer 注册表 — Kotlin 驱动。
 *
 * 与旧版 (asyncFunction + Promise 链) 的区别: "等待"与"重复"都在 Kotlin 协程里完成,
 * 不产生 JS async job。因此 evaluate 的贪婪 awaitAsyncJobs 不会去 join 未来 tick,
 * 顶层 setInterval/setTimeout 不再挂死脚本加载、不阻塞工具调用。
 *
 * 定时器协程跑在 runtime 专属单线程 dispatcher 上, 与工具调用共享 JS 单线程:
 * 触发是 best-effort (被进行中的工具调用挡住会延后), 回调慢会挡住工具调用 ——
 * 这是单线程 JS 的固有约束, 不是缺陷。
 *
 * 回调异常: 走 host.console.error, 不让单次失败终止整个定时器;
 * 引擎级异常 (evaluate 抛出) 通过 onFailure 记录到 println, 不静默吞掉。
 * 一次性 timer 触发后自动从 jobs / globalThis.__tl 删除条目, 避免递归 setTimeout
 * 等场景下无限增长 (幂等性不受影响: clearTimeout 对已完成 Job 是 no-op)。
 * setInterval 间隔下限 [MIN_INTERVAL_MS] (4ms, 对齐浏览器), 防止 delay(0) 循环
 * 独占单线程 dispatcher 饿死工具调用。
 */
private class TimerRegistry(
    private val entry: RuntimeEntry,
    private val namespace: String,
) {
    private val jobs = mutableMapOf<Long, Job>()
    private var nextId = 1L

    @Synchronized
    fun nextId(): Long = nextId++

    @Synchronized
    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
    }

    fun schedule(id: Long, millis: Long, repeat: Boolean) {
        // setInterval 间隔下限: 对齐浏览器 ~4ms, 避免 delay(0) 独占单线程 dispatcher。
        // setTimeout(0) 不做限制 (保留 "yield to next tick" 惯用法)。
        val effectiveMillis = if (repeat) maxOf(millis, MIN_INTERVAL_MS) else millis
        val job = entry.scope.launch(entry.dispatcher) {
            if (repeat) {
                while (coroutineContext.isActive && !entry.quickJs.isClosed) {
                    delay(effectiveMillis)
                    if (entry.quickJs.isClosed) return@launch
                    runCallback(id)
                }
            } else {
                delay(effectiveMillis)
                if (entry.quickJs.isClosed) return@launch
                runCallback(id)
                cleanupOneShot(id)
            }
        }
        synchronized(this) { jobs[id] = job }
    }

    /**
     * 一次性 timer 触发后清理: 从 [jobs] 和 globalThis.__tl 移除条目。
     *
     * 幂等: 若已被 clearTimeout 清掉, jobs.remove 返回 null, delete 不存在属性是 no-op。
     * 注意这里的 runJs evaluate 会获取 jsMutex 并贪婪 awaitAsyncJobs, 但一次性 timer
     * 触发后通常无待处理异步作业, 影响可忽略。
     */
    private suspend fun cleanupOneShot(id: Long) {
        synchronized(this) { jobs.remove(id) }
        runCatching {
            entry.runJs { evaluate<Any?>("delete globalThis.__tl[$id];") }
        }.onFailure { e ->
            println("[host.timer] [$namespace] cleanupOneShot($id) evaluate 失败: ${e.message}")
        }
    }

    private suspend fun runCallback(id: Long) {
        runCatching {
            entry.runJs {
                evaluate<Any?>(
                    "if (globalThis.__tl && globalThis.__tl[$id]) { " +
                        "try { globalThis.__tl[$id]() } catch (e) { host.console.error(String(e)) } }"
                )
            }
        }.onFailure { e ->
            // 引擎级异常 (如关闭竞争、JS 语法问题): JS 内的 try/catch 捕不到, 这里兜底记录。
            println("[host.timer] [$namespace] runCallback($id) evaluate 失败: ${e.message}")
        }
    }

    private companion object {
        /** setInterval 间隔下限 (ms), 对齐浏览器 ~4ms 约定。 */
        const val MIN_INTERVAL_MS = 4L
    }
}
