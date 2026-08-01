package io.github.totomika.pocketmcp.runtime

import io.github.totomika.pocketmcp.script.RuntimeConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 管理所有脚本运行时的生命周期。
 *
 * 核心原则: 1 脚本 = 1 Runtime (见 docs/05-runtime.md)。
 * 多个 Profile 引用同一脚本时, 共享同一 runtime, 引用计数管理。
 *
 * - refCount 0→1: 创建 runtime, evaluate 脚本, 启动 pump + health check
 * - refCount >1: 无操作 (runtime 已存在)
 * - refCount →0: 销毁 runtime, 停止 pump + health check, 释放 native 内存
 */
class RuntimeManager(
    private val runtimeFactory: RuntimeFactory = RuntimeFactory(),
) {

    private val runtimes = mutableMapOf<String, RuntimeEntry>()
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
    ): RuntimeEntry = mutex.withLock {
        val existing = runtimes[namespace]
        if (existing != null) {
            if (existing.poisoned) {
                // 中毒的 runtime (OOM/超时导致引擎损坏): 销毁重建, 不复用。
                // 保留旧 refCount: 其他服务仍持有引用, 重建后它们的引用转移到新 runtime,
                // 不能重置为 1, 否则其他服务 release 时会过早销毁新 runtime。
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

    /**
     * 释放引用。计数归零时销毁 runtime。
     */
    suspend fun release(namespace: String) = mutex.withLock {
        val entry = runtimes[namespace] ?: return@withLock
        entry.refCount--
        if (entry.refCount <= 0) {
            entry.destroy()
            runtimes.remove(namespace)
        }
    }

    /** 查找 runtime (不增加引用计数)。 */
    fun getRuntime(namespace: String): RuntimeEntry? = runtimes[namespace]

    /** 活跃 runtime 数量。 */
    fun activeCount(): Int = runtimes.size

    /**
     * 动态更新运行时的 memoryLimit / maxStackSize。
     *
     * 若 runtime 正在运行, 直接通过 quickJs setter 热更新;
     * 若未运行, 下次 [acquire] 创建时从 manifest 读取生效。
     *
     * @param config 新配置; null 字段 = 不限制 (0), 两者都 null = 恢复默认
     */
    fun updateRuntimeConfig(namespace: String, config: RuntimeConfig) {
        val entry = runtimes[namespace] ?: return
        // 0 = 无限制, 需转为极大值 (QuickJS 的 0 = "0 字节可用")
        entry.quickJs.memoryLimit = config.memoryLimit?.let { if (it == 0L) RuntimeFactory.UNLIMITED else it } ?: RuntimeFactory.DEFAULT_MEMORY_LIMIT
        entry.quickJs.maxStackSize = config.maxStackSize?.let { if (it == 0L) RuntimeFactory.UNLIMITED else it } ?: RuntimeFactory.DEFAULT_MAX_STACK_SIZE
    }

    /** 销毁所有 runtime。 */
    suspend fun destroyAll() = mutex.withLock {
        runtimes.values.forEach { it.destroy() }
        runtimes.clear()
    }
}
