package io.github.totomika.pocketmcp.runtime

import io.github.totomika.pocketmcp.script.RuntimeConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理所有脚本运行时的生命周期。
 *
 * 核心原则: 1 脚本 = 1 Runtime。
 * 多个 Profile 引用同一脚本时, 共享同一 runtime, 引用计数管理。
 *
 * - refCount 0→1: 创建 runtime, evaluate 脚本, 启动 health check
 * - refCount >1: 无操作 (runtime 已存在)
 * - refCount →0: 销毁 runtime, 停止 health check, 释放 native 内存
 *
 * 线程模型: 所有 suspend 公共方法内部切换到 [ioDispatcher] (默认 Dispatchers.IO): 调用方无论
 * 在什么线程 (含 Main), manager 操作必然离开调用方上下文执行。这取代了原先散落在
 * 各 ViewModel 的 withContext(Dispatchers.Default) 补丁。
 */
class RuntimeManager(
    private val runtimeFactory: RuntimeFactory = RuntimeFactory(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // ConcurrentHashMap: getRuntime/activeCount 被 Ktor 请求线程高频无锁读,
    // 与 acquire/release (mutex 内) 的结构性写入并发 —— HashMap 在此存在数据竞态
    // (丢条目/脏读)。写侧仍由 mutex 串行化, CHM 只保证读侧安全。
    private val runtimes = ConcurrentHashMap<String, RuntimeEntry>()
    private val mutex = Mutex()

    /**
     * 获取或创建指定 namespace 的 runtime。
     *
     * 引用计数 +1。首次创建时 evaluate 脚本并启动后台 Job。
     *
     * @param runtimeConfig 高级配置, 首次创建时使用; 已存在时忽略 (用 [updateRuntimeConfig] 动态变更)
     * @throws com.dokar.quickjs.QuickJsException 脚本 evaluate 失败
     */
    suspend fun acquire(
        namespace: String,
        scriptCode: String,
        runtimeConfig: RuntimeConfig? = null,
    ): RuntimeEntry = withContext(ioDispatcher) {
        mutex.withLock {
            val existing = runtimes[namespace]
            if (existing != null) {
                if (existing.poisoned) {
                    // 中毒的 runtime (OOM/超时导致引擎损坏): 销毁重建, 不复用。
                    // 保留旧 refCount: 其他服务仍持有引用, 重建后它们的引用转移到新 runtime,
                    // 不能重置为 1, 否则其他服务 release 时会过早销毁新 runtime。
                    // 注意: destroy() 是探测式销毁, 死循环卡死时走孤儿化, 永不阻塞本 mutex。
                    val preservedRef = existing.refCount
                    existing.destroy()
                    runtimes.remove(namespace)

                    val entry = runtimeFactory.create(namespace, scriptCode, runtimeConfig)
                    runtimeFactory.startBackgroundJobs(entry)
                    entry.refCount = preservedRef + 1 // +1 = 当前调用者的新引用
                    runtimes[namespace] = entry
                    return@withLock entry
                } else {
                    existing.refCount++
                    return@withLock existing
                }
            }

            val entry = runtimeFactory.create(namespace, scriptCode, runtimeConfig)
            runtimeFactory.startBackgroundJobs(entry)

            entry.refCount = 1
            runtimes[namespace] = entry
            entry
        }
    }

    /**
     * 释放引用。计数归零时销毁 runtime。
     */
    suspend fun release(namespace: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val entry = runtimes[namespace] ?: return@withLock
            entry.refCount--
            if (entry.refCount <= 0) {
                entry.destroy()
                runtimes.remove(namespace)
            }
        }
    }

    /** 查找 runtime (不增加引用计数)。 */
    fun getRuntime(namespace: String): RuntimeEntry? = runtimes[namespace]

    /** 活跃 runtime 数量。 */
    fun activeCount(): Int = runtimes.size

    /**
     * 动态更新运行时的 memoryLimit / maxStackSize。
     *
     * best-effort: 若 runtime 正在运行, 经 [RuntimeEntry.runJs] 在 runtime 专属线程上热更新
     * (native setter 与 JS 执行同线程, 避免并发访问 native ctx); 已中毒/已关闭/派发失败
     * (如与销毁竞争) 时静默跳过 —— manifest 已持久化, 下次 acquire 自然生效。
     * 不设无界挂起: 探针级超时兜底 (对 STUCK 的 runtime 调 runJs 会永久挂起)。
     */
    suspend fun updateRuntimeConfig(namespace: String, config: RuntimeConfig) {
        val entry = runtimes[namespace] ?: return
        if (entry.poisoned || entry.quickJs.isClosed) return
        // 0 = 无限制, 需转为极大值 (QuickJS 的 0 = "0 字节可用")
        val memLimit = config.memoryLimit?.let { if (it == 0L) RuntimeFactory.UNLIMITED else it }
            ?: RuntimeFactory.DEFAULT_MEMORY_LIMIT
        val stackSize = config.maxStackSize?.let { if (it == 0L) RuntimeFactory.UNLIMITED else it }
            ?: RuntimeFactory.DEFAULT_MAX_STACK_SIZE
        runCatching {
            withTimeoutOrNull(RuntimePolicy.PROBE_TIMEOUT_MS) {
                entry.runJs {
                    memoryLimit = memLimit
                    maxStackSize = stackSize
                }
            }
        }
    }

    /** 销毁所有 runtime。 */
    suspend fun destroyAll() = withContext(ioDispatcher) {
        mutex.withLock {
            for (entry in runtimes.values) {
                // 单个 runtime 销毁失败 (如 onDestroy 清理异常) 不应中断其余 (退出路径, 尽力而为)
                runCatching { entry.destroy() }
            }
            runtimes.clear()
        }
    }
}
