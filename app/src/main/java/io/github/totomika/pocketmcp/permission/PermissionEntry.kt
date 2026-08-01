package io.github.totomika.pocketmcp.permission

import kotlinx.serialization.Serializable

/**
 * 权限条目 (单条声明 + 授权状态)。
 *
 * 落点: `scripts/<ns>/manifest.json` 的 `permissions` 数组中。
 * 每条 = 一个 (token, spec) 组合, 同 token 不同 spec 会各占一行
 * (例如多条 host.fs.shared.read glob)。
 *
 * - `fs.shared.read/write`: token 存 "host.fs.shared.read", spec 存 glob 路径
 * - 其他 token: spec 为 null
 *
 * ## declared 字段
 * `declared=true` 表示脚本头部 `@permission` 声明中包含此权限
 * (即"脚本主动要求的"), 这是常规情况。
 * `declared=false` 表示用户在脚本未声明的情况下手动 grant 了此权限
 * (历史情形, 重构后允许保留)。该字段不影响运行时检查, 仅用于 UI
 * 区分"声明内授权"与"声明外额外授权"。
 *
 * 见 docs/04-permissions.md "授权流程"。
 *
 * (token, spec) 唯一: 同一脚本同一权限同一 glob 只有一条记录。
 */
@Serializable
data class PermissionEntry(
    /** 权限 token 字符串, 对应 [io.github.totomika.pocketmcp.permission.PermissionToken.token]。 */
    val token: String,
    /** glob 路径, 仅 fs.shared.read/write 有, 其他为 null。 */
    val spec: String? = null,
    /** 是否已授权。granted=false 时调用受限 API 会抛 SecurityException。 */
    val granted: Boolean = false,
    /**
     * 是否是脚本 `@permission` 声明内权限 (区别于用户手动 grant 的声明外权限)。
     * syncOnUpdate 用此字段判断是否要在脚本更新时删除声明消失的项。
     */
    val declared: Boolean = true,
)
