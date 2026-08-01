package io.github.totomika.pocketmcp.permission

import io.github.totomika.pocketmcp.R

/**
 * 权限 token 枚举。
 *
 * 见 docs/04-permissions.md "权限 token 对照表"。
 *
 * - FS_SHARED_READ / FS_SHARED_WRITE: 需要 spec (glob 路径)
 * - 其他: 无 spec, 全有或全无
 *
 * write 隐含 read: 持有 FS_SHARED_WRITE 的 glob 同时允许 read。
 */
enum class PermissionToken(val token: String, val requiresSpec: Boolean) {
    FS_SHARED_READ("host.fs.shared.read", requiresSpec = true),
    FS_SHARED_WRITE("host.fs.shared.write", requiresSpec = true),
    FETCH("host.fetch", requiresSpec = false),
    CLIPBOARD("host.clipboard", requiresSpec = false),
    DEVICE_INFO("host.deviceInfo", requiresSpec = false),
    TOAST("host.toast", requiresSpec = false),
    OPEN_URL("host.openUrl", requiresSpec = false);

    companion object {
        /** 从字符串解析 token, 未识别返回 null。 */
        fun fromString(raw: String): PermissionToken? =
            entries.firstOrNull { it.token == raw }

        /** write 隐含 read: 若 token 为 READ, WRITE 也满足。 */
        fun implies(granted: PermissionToken, requested: PermissionToken): Boolean {
            if (granted == requested) return true
            // WRITE 隐含 READ
            if (requested == FS_SHARED_READ && granted == FS_SHARED_WRITE) return true
            return false
        }
    }
}

/**
 * 脚本头部声明的单条权限。
 *
 * @param token 权限类型
 * @param spec  glob 路径 (仅 fs.shared.read/write 有), 其他为 null
 */
data class PermissionDeclaration(
    val token: PermissionToken,
    val spec: String?,
)

/**
 * 权限的人类可读描述, 供 UI 展示用。
 *
 * [description] 不再保存已拼好的文案, 而是保存字符串资源 id + 格式参数,
 * 由渲染层（@Composable）通过 `stringResource(descriptionRes, *descriptionArgs)` 解析,
 * 以支持 i18n。
 */
data class PermissionDisplay(
    val token: PermissionToken,
    val spec: String?,
    val icon: String,
    val descriptionRes: Int,
    val descriptionArgs: Array<Any>,
    val recursive: Boolean,
) {
    companion object {
        fun from(decl: PermissionDeclaration): PermissionDisplay {
            return when (decl.token) {
                PermissionToken.FS_SHARED_READ -> {
                    val recursive = decl.spec?.contains("**") == true
                    PermissionDisplay(
                        token = decl.token,
                        spec = decl.spec,
                        icon = "\uD83D\uDCC1", // 📁
                        descriptionRes = R.string.perm_read,
                        descriptionArgs = arrayOf(decl.spec ?: "*"),
                        recursive = recursive,
                    )
                }

                PermissionToken.FS_SHARED_WRITE -> {
                    val recursive = decl.spec?.contains("**") == true
                    PermissionDisplay(
                        token = decl.token,
                        spec = decl.spec,
                        icon = "\u270F\uFE0F", // ✏️
                        descriptionRes = R.string.perm_write,
                        descriptionArgs = arrayOf(decl.spec ?: "*"),
                        recursive = recursive,
                    )
                }

                PermissionToken.FETCH -> PermissionDisplay(
                    token = decl.token, spec = null,
                    icon = "\uD83C\uDF10", // 🌐
                    descriptionRes = R.string.perm_fetch,
                    descriptionArgs = emptyArray(),
                    recursive = false,
                )

                PermissionToken.CLIPBOARD -> PermissionDisplay(
                    token = decl.token, spec = null,
                    icon = "\uD83D\uDCCB", // 📋
                    descriptionRes = R.string.perm_clipboard,
                    descriptionArgs = emptyArray(),
                    recursive = false,
                )

                PermissionToken.DEVICE_INFO -> PermissionDisplay(
                    token = decl.token, spec = null,
                    icon = "\uD83D\uDCF1", // 📱
                    descriptionRes = R.string.perm_device_info,
                    descriptionArgs = emptyArray(),
                    recursive = false,
                )

                PermissionToken.TOAST -> PermissionDisplay(
                    token = decl.token, spec = null,
                    icon = "\uD83D\uDCAC", // 💬
                    descriptionRes = R.string.perm_toast,
                    descriptionArgs = emptyArray(),
                    recursive = false,
                )

                PermissionToken.OPEN_URL -> PermissionDisplay(
                    token = decl.token, spec = null,
                    icon = "\uD83D\uDD17", // 🔗
                    descriptionRes = R.string.perm_open_url,
                    descriptionArgs = emptyArray(),
                    recursive = false,
                )
            }
        }
    }
}
