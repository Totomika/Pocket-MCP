package io.github.totomika.pocketmcp.host

import io.github.totomika.pocketmcp.runtime.RuntimeEntry

/**
 * host.* API 注入接口。
 *
 * 每个 host API 实现此接口, 由 HostApiRegistry 统一注册。
 */
interface HostApi {
    /**
     * 注入 API 到当前 runtime。
     *
     * 绑定注册 (function / asyncFunction) 是 define 操作, 不执行 JS, 直接用
     * [RuntimeEntry.quickJs] 注册; 注入期的 JS glue (evaluate) 一律经
     * [RuntimeEntry.runJs] 在 runtime 专属线程执行, 不得在任何线程直接 evaluate。
     *
     * @param entry 目标 runtime (提供 quickJs / scope / runJs)
     * @param namespace 当前脚本 namespace (用于数据隔离)
     */
    suspend fun inject(entry: RuntimeEntry, namespace: String)

    /**
     * 清理指定 namespace 持有的资源 (DB handles, connections 等)。
     *
     * 在 Runtime 销毁时由 HostApiRegistry 调用。
     * 默认空实现, 需要清理资源的 API 自行覆盖。
     */
    fun cleanup(namespace: String) {}
}
