package io.github.totomika.pocketmcp.script

import io.github.totomika.pocketmcp.permission.PermissionEntry
import io.github.totomika.pocketmcp.script.ScriptManifest.Companion.COMPANION_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 脚本清单 (manifest)。落点: `files/scripts/<ns>/manifest.json`。
 *
 * 把脚本元数据 + 权限声明 + 授权状态合并到单文件, 与脚本源码 (src/) 同级。
 * 这样卸载脚本 = 删除整个 `scripts/<ns>/` 目录, 真正达到原子语义。
 *
 * - `metadata`: 脚本身份信息 (来源 / 版本 / 描述 ...) — 只在 import / update 时写
 * - `permissions`: 权限声明 + 授权状态 — 用户每次 grant/revoke 或脚本更新时写
 * - `enabled`: 预留位, 给未来的"禁用脚本"开关使用; 当前恒为 true
 *
 * 顶层 `version` 是本 schema 的版本号, 供后续升级兼容用; 与脚本自身 `scriptVersion` 字段不同。
 */
@Serializable
data class ScriptManifest(
    /** manifest schema 版本。当前固定为 [COMPANION_VERSION]。 */
    val version: Int = COMPANION_VERSION,
    @SerialName("metadata")
    val metadata: Metadata,
    /**
     * 权限条目数组。每条 = 一个 (token, spec) 组合的声明与授权状态。
     * 同 token 不同 spec 会各占一行 (如多条 fs.shared.read glob)。
     */
    val permissions: List<PermissionEntry> = emptyList(),
    /**
     * 预留: 脚本启用/禁用开关。当前不暴露 UI, 恒为 true。
     * 后续若做"不卸载只停用"功能, 这个字段即开关位。
     */
    val enabled: Boolean = true,
    /**
     * 运行时高级配置 (memoryLimit / maxStackSize)。
     * null = 使用默认值 (16MB / 512KB)。用户可在脚本详情页高级设置中修改。
     */
    val runtimeConfig: RuntimeConfig? = null,
) {
    /**
     * 脚本元数据。对应原 Room `ScriptEntry`, 字段语义保持一致。
     * 用短名 `Metadata` 嵌入 `ScriptManifest` 内层, 通过文件路径而非单独表定位。
     */
    @Serializable
    data class Metadata(
        @SerialName("namespace") val namespace: String,
        @SerialName("name") val name: String,
        @SerialName("scriptVersion") val scriptVersion: String,
        @SerialName("description") val description: String,
        @SerialName("author") val author: String? = null,
        @SerialName("instructions") val instructions: String? = null,
        @SerialName("homepage") val homepage: String? = null,
        @SerialName("minAppVersion") val minAppVersion: String? = null,
        /** 导入渠道: "FILE" | "PASTE" | "URL" (对应 ScriptSourceType.name) */
        @SerialName("sourceType") val sourceType: String,
        @SerialName("sourceUrl") val sourceUrl: String? = null,
        @SerialName("importedAt") val importedAt: Long = System.currentTimeMillis(),
        @SerialName("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    )

    companion object {
        /** manifest schema 当前版本。新增字段时递增; 因 `ignoreUnknownKeys=true`,
         *  旧版 manifest 缺新字段会自动用默认值 (null), 无需显式迁移逻辑。
         *  仅当需要改变已有字段的语义/类型时才需写迁移。 */
        const val COMPANION_VERSION: Int = 2
    }
}

/**
 * 脚本运行时高级配置。
 *
 * 存储在 [ScriptManifest.runtimeConfig] 中, 由用户在脚本详情页高级设置中配置。
 * 各字段值 0 = 不限制 (unlimited), null = 使用默认值。
 *
 * @param memoryLimit QuickJS 堆内存上限 (字节)。0 = 不限制; null = 默认 16MB。
 * @param maxStackSize JS 调用栈 (native 栈) 上限 (字节)。0 = 不限制; null = 默认 512KB。
 */
@Serializable
data class RuntimeConfig(
    @SerialName("memoryLimit") val memoryLimit: Long? = null,
    @SerialName("maxStackSize") val maxStackSize: Long? = null,
)