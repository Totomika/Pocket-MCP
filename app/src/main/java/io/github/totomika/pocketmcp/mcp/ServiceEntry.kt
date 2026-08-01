package io.github.totomika.pocketmcp.mcp

/**
 * 服务 (MCP Server 实例) 内存读模。
 *
 * 一个 Service = 一个 MCP Server 实例 = 一个端口 + 一组脚本工具子集。
 * 持久化由 [ServiceManifestStore] 落到 `files/services/<svcId>/manifest.json`,
 * 本类只在内存中流转 (UI / 运行时); 字段与 [ServiceManifest] 一一对应。
 *
 * `id` 用短 UUID (与 manifest 文件名一致), 跨设备稳定, 端口可改不影响身份。
 *
 * - name: 用户可见名称 (唯一)
 * - port: 监听端口 (127.0.0.1)
 * - enabled: 是否启动 (前台服务恢复时用)
 * - autoCreated: 是否自动创建的 per-script 服务 (历史字段, 现通常为 false)
 */
data class ServiceEntry(
    val id: String,
    val name: String,
    val port: Int,
    val enabled: Boolean = false,
    val autoCreated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)