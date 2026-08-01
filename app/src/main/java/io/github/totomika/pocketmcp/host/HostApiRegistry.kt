package io.github.totomika.pocketmcp.host

import com.dokar.quickjs.QuickJs
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import io.github.totomika.pocketmcp.permission.SystemPermissionChecker
import kotlinx.coroutines.CoroutineScope

/**
 * 统一注册所有 host.* API。
 *
 * 由 RuntimeFactory 调用, 注入第 0 层 (console/timer/crypto, 在 runtime 包)
 * + 第 1-4 层 API (kv/sql/fs/fetch/system, 在 host 包)。
 */
class HostApiRegistry(
    private val apis: List<HostApi>,
) {
    /**
     * 注入所有 API。
     */
    fun injectAll(quickJs: QuickJs, namespace: String, scope: CoroutineScope) {
        for (api in apis) {
            api.inject(quickJs, namespace, scope)
        }
    }

    /**
     * 清理所有 API 在指定 namespace 持有的资源。
     */
    fun cleanupAll(namespace: String) {
        for (api in apis) {
            api.cleanup(namespace)
        }
    }

    companion object {
        /**
         * 创建默认的 API 列表 (M3 完整 + M4 权限检查)。
         *
         * @param context Android Context (kv/sql/fs/system 需要)
         * @param fsPermissionChecker fs.global 权限检查 (M4, null 时跳过)
         * @param fetchPermissionChecker fetch 权限检查 (M4, null 时跳过)
         * @param systemPermissionChecker system 权限检查 (M4, null 时跳过)
         */
        fun createDefault(
            pathManager: FsPathManager,
            context: android.content.Context,
            fsPermissionChecker: FsPermissionChecker? = null,
            fetchPermissionChecker: FetchPermissionChecker? = null,
            systemPermissionChecker: SystemPermissionChecker? = null,
        ): List<HostApi> = listOf(
            KvApi(pathManager),
            SqlApi(pathManager),
            FsApi(pathManager, fsPermissionChecker),
            FetchApi(fetchPermissionChecker),
            SystemApi(context, systemPermissionChecker),
        )
    }
}
