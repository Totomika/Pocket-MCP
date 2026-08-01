package io.github.totomika.pocketmcp.script

/**
 * 脚本元数据 (内存数据类)。
 *
 * ⚠ 历史保留: 此类原为 Room 实体, 重构后实际持久化已迁至
 * `ScriptManifest` (见 `files/scripts/<ns>/manifest.json`)。
 * 本类仍保留作为 UI / ViewModel 用的内存读模, 由
 * [ScriptManifest.Metadata] ↔ [ScriptEntry] 互转得到。
 *
 * - namespace 唯一: 导入相同 namespace = 更新
 * - sourceType: 导入渠道 (FILE/PASTE/URL)
 * - sourceUrl: URL 导入的源地址 (仅 URL 类型有值, 用于更新检查)
 *
 * 字段语义与 [ScriptManifest.Metadata] 一一对应, 除 `id` 外
 * (manifest 无 Long id, 用 namespace 作主键)。
 *
 * 见 docs/08-distribution.md "元数据格式"。
 */
data class ScriptEntry(
    /** 历史主键。重构后已无持久化语义, 仅为兼容旧调用方保留; 新代码不应依赖此值。 */
    val id: Long = 0,
    val namespace: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String? = null,
    val instructions: String? = null,
    val homepage: String? = null,
    val minAppVersion: String? = null,
    /** 导入渠道 */
    val sourceType: String, // ScriptSourceType.name
    /** URL 导入的源地址 (仅 URL 类型) */
    val sourceUrl: String? = null,
    val importedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
