package io.github.totomika.pocketmcp.permission

import android.content.Context
import android.os.Environment
import io.github.totomika.pocketmcp.host.FetchPermissionChecker
import io.github.totomika.pocketmcp.host.FsPermissionChecker
import io.github.totomika.pocketmcp.script.ScriptManifestStore
import kotlinx.coroutines.runBlocking

/**
 * 系统能力权限检查接口 (clipboard/deviceInfo/toast/openUrl)。
 *
 * SystemApi 调用受限 API 前检查, 无权限时抛 SecurityException。
 * M4 由 ScriptPermissionChecker 实现。
 */
interface SystemPermissionChecker {
    /**
     * 检查指定 token 是否已授权。
     * @param namespace 脚本 namespace (== scriptId)
     * @param token 权限 token (如 PermissionToken.CLIPBOARD)
     * @throws SecurityException 未授权时
     */
    fun check(namespace: String, token: PermissionToken)
}

/**
 * 运行时权限检查器 (脚本级权限, "分闸")。
 *
 * 实现 FsPermissionChecker + FetchPermissionChecker + SystemPermissionChecker,
 * 接入 FsApi / FetchApi / SystemApi。
 *
 * 重构后权限读自 [ScriptManifestStore] (单 JSON), 不再依赖 Room DAO。
 *
 * 两层权限检查:
 * 1. App 系统权限 (总闸): MANAGE_EXTERNAL_STORAGE / INTERNET
 * 2. 脚本权限 (分闸): manifest.json 的 permissions 字段 granted=true 记录
 *
 * 见 docs/04-permissions.md "运行时检查"。
 *
 * 注意: [ScriptManifestStore] 方法是 suspend, 但接口方法不是 (因 SystemApi 用同步 function 绑定)。
 * 内部用 runBlocking 桥接, manifest 读取很快 (单文件 + 缓存友好), 可接受。
 *
 * @param manifestStore 脚本清单存储
 * @param context Android Context (系统权限检查用)
 */
class ScriptPermissionChecker(
    private val manifestStore: ScriptManifestStore,
    private val context: Context,
) : FsPermissionChecker, FetchPermissionChecker, SystemPermissionChecker {

    /** 设备实际的 SD 卡根路径, 用于 [PathMatcher] 的 `~` 展开。 */
    private val sdcardRoot: String = Environment.getExternalStorageDirectory().absolutePath

    /**
     * 检查 fs.shared 读权限。
     *
     * SECURITY: 此处检查权限
     *
     * 1. 系统权限: MANAGE_EXTERNAL_STORAGE
     * 2. 脚本权限: host.fs.shared.read 或 host.fs.shared.write (write 隐含 read)
     *    的任一已授权 glob 匹配请求路径
     * 3. symlink 防护: PathMatcher 对 canonicalPath 匹配
     */
    override fun checkRead(namespace: String, path: String) {
        // 第 1 层: 系统权限
        if (!SystemPermissionHelper.checkFsSharedSystemPermission(context)) {
            throw SecurityException(
                "Permission denied: host.fs.shared.read at $path. " +
                        "System permission MANAGE_EXTERNAL_STORAGE not granted. " +
                        "Please grant in Settings."
            )
        }

        // 第 2 层: 脚本权限 (从 manifest grants)
        val grantedPerms = runBlocking { grantedFor(namespace) }
        val readGlobs = grantedPerms
            .filter { it.token == PermissionToken.FS_SHARED_READ.token }
            .mapNotNull { it.spec }
        val writeGlobs = grantedPerms
            .filter { it.token == PermissionToken.FS_SHARED_WRITE.token }
            .mapNotNull { it.spec }

        // write 隐含 read: write 的 glob 也匹配 read 请求
        val allGlobs = readGlobs + writeGlobs

        if (allGlobs.isEmpty()) {
            throw SecurityException(
                "Permission denied: host.fs.shared.read at $path. " +
                        "No granted paths for namespace=$namespace."
            )
        }

        if (!PathMatcher.isAnyGranted(allGlobs, path, sdcardRoot = sdcardRoot)) {
            throw SecurityException(
                "Permission denied: host.fs.shared.read at $path. " +
                        "Granted globs: $allGlobs. Path not matched."
            )
        }
    }

    /**
     * 检查 fs.shared 写权限。
     *
     * SECURITY: 此处检查权限
     */
    override fun checkWrite(namespace: String, path: String) {
        // 第 1 层: 系统权限
        if (!SystemPermissionHelper.checkFsSharedSystemPermission(context)) {
            throw SecurityException(
                "Permission denied: host.fs.shared.write at $path. " +
                        "System permission MANAGE_EXTERNAL_STORAGE not granted. " +
                        "Please grant in Settings."
            )
        }

        // 第 2 层: 脚本权限
        val grantedPerms = runBlocking { grantedFor(namespace) }
        val writeGlobs = grantedPerms
            .filter { it.token == PermissionToken.FS_SHARED_WRITE.token }
            .mapNotNull { it.spec }

        if (writeGlobs.isEmpty()) {
            throw SecurityException(
                "Permission denied: host.fs.shared.write at $path. " +
                        "No granted write paths for namespace=$namespace."
            )
        }

        if (!PathMatcher.isAnyGranted(writeGlobs, path, sdcardRoot = sdcardRoot)) {
            throw SecurityException(
                "Permission denied: host.fs.shared.write at $path. " +
                        "Granted write globs: $writeGlobs. Path not matched."
            )
        }
    }

    /**
     * 检查 host.fetch 权限。
     *
     * SECURITY: 此处检查权限
     */
    override fun check(namespace: String, url: String) {
        // 第 1 层: 系统权限
        if (!SystemPermissionHelper.checkFetchSystemPermission(context)) {
            throw SecurityException(
                "Permission denied: host.fetch to $url. " +
                        "System permission INTERNET not granted."
            )
        }

        // 第 2 层: 脚本权限
        val grantedPerms = runBlocking { grantedFor(namespace) }
        if (grantedPerms.none { it.token == PermissionToken.FETCH.token }) {
            throw SecurityException(
                "Permission denied: host.fetch to $url. " +
                        "host.fetch not granted for namespace=$namespace."
            )
        }
    }

    /**
     * 检查系统能力权限 (clipboard/deviceInfo/toast/openUrl)。
     *
     * SECURITY: 此处检查权限
     */
    override fun check(namespace: String, token: PermissionToken) {
        val grantedPerms = runBlocking { grantedFor(namespace) }
        if (grantedPerms.none { it.token == token.token }) {
            throw SecurityException(
                "Permission denied: ${token.token} for namespace=$namespace. " +
                        "Not granted."
            )
        }
    }

    /** 读取 namespace 清单的已授权权限条目。 */
    private suspend fun grantedFor(namespace: String): List<PermissionEntry> {
        return manifestStore.read(namespace)?.permissions?.filter { it.granted } ?: emptyList()
    }
}
