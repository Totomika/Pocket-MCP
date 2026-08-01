package io.github.totomika.pocketmcp.host

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineScope

/**
 * host.* API 注入接口。
 *
 * 每个 host API 实现此接口, 由 HostApiRegistry 统一注册。
 */
interface HostApi {
    /**
     * 注入 API 到 QuickJS。
     *
     * @param quickJs QuickJs 实例
     * @param namespace 当前脚本 namespace (用于数据隔离)
     * @param scope 协程作用域 (用于异步操作)
     */
    fun inject(quickJs: QuickJs, namespace: String, scope: CoroutineScope)

    /**
     * 清理指定 namespace 持有的资源 (DB handles, connections 等)。
     *
     * 在 Runtime 销毁时由 HostApiRegistry 调用。
     * 默认空实现, 需要清理资源的 API 自行覆盖。
     */
    fun cleanup(namespace: String) {}
}
