package io.github.totomika.pocketmcp.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 服务清单 (manifest)。落点: `files/services/<svc-id>/manifest.json`。
 *
 * 一个 Service = 一个 MCP Server 实例 = 一个端口 + 一组脚本工具子集。
 * 旧名 "Profile", 重构后改名 "Service" 减少命名歧义 (Android 语境下 Profile
 * 有 UserProfile 等强干扰含义, 而本类对用户暴露的是端口号 + 启停, 直称"服务"更准)。
 *
 * 把配置与启用的脚本列表合并到单文件, 服务删除 = 删目录, 真正原子。
 * 关联的脚本通过 `scripts` 数组的 namespace 引用; 删除脚本时扫描所有
 * `services/<svcId>/manifest.json`, 移除引用此 ns 的项 (替代原 Room CASCADE 的语义,
 * 修正之前 "CASCADE 只在删 service 时触发, 删脚本时不触发" 的 bug)。
 *
 * `id` 用短 UUID (8 字符 base62), 文件名就用它, 跨设备稳定, 端口可改不影响身份。
 */
@Serializable
data class ServiceManifest(
    /** manifest schema 版本。 */
    val version: Int = COMPANION_VERSION,
    /** 服务 ID (短 UUID, 等于文件名)。 */
    @SerialName("id") val id: String,
    /** 用户可见名称 (唯一)。 */
    @SerialName("name") val name: String,
    /** 监听端口 (127.0.0.1:<port>)。 */
    @SerialName("port") val port: Int,
    /** 是否启动 (前台服务恢复时用)。 */
    @SerialName("enabled") val enabled: Boolean = false,
    /**
     * 历史字段: 是否为导入脚本时自动创建的 per-script 服务。
     * 现通常为 false (已不再自动创建)。保留以便 UI 区分兼容旧数据。
     */
    @SerialName("autoCreated") val autoCreated: Boolean = false,
    @SerialName("createdAt") val createdAt: Long = System.currentTimeMillis(),
    /** 该服务包含的脚本引用列表 (相当于原 profile_scripts 表)。 */
    @SerialName("scripts") val scripts: List<ScriptRef> = emptyList(),
) {
    /**
     * 服务内单个脚本引用。
     * enabled: 该脚本在此服务中是否启用 (工具白名单开关)。
     */
    @Serializable
    data class ScriptRef(
        @SerialName("namespace") val namespace: String,
        @SerialName("enabled") val enabled: Boolean = true,
    )

    companion object {
        const val COMPANION_VERSION: Int = 1
    }
}