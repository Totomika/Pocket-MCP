package io.github.totomika.pocketmcp.script

/**
 * 脚本元数据。
 *
 * 从脚本头部注释 `// @field value` 静态解析。
 * 见 docs/02-script-api.md "头部注释元数据"。
 *
 * 必填字段: name, namespace, version, description
 * 可选字段: author, instructions, homepage, minAppVersion
 *
 * @property namespace 唯一身份标识, 正则 ^[a-z][a-z0-9-]*$
 * @property version 语义版本号 (如 1.0.0)
 * @property instructions 给 AI 看的使用说明 (多行)
 * @property minAppVersion 兼容性检查, 低于此版本拒绝导入
 */
data class ScriptMetadata(
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val author: String? = null,
    val instructions: String? = null,
    val homepage: String? = null,
    val minAppVersion: String? = null,
) {
    /**
     * 校验必填字段和格式。
     *
     * @throws IllegalArgumentException 校验失败
     */
    fun validate() {
        require(name.isNotBlank()) { "@name is required" }
        require(namespace.matches(NAMESPACE_REGEX)) {
            "@namespace must match ^[a-z][a-z0-9-]*\$: $namespace"
        }
        require(version.matches(VERSION_REGEX)) {
            "@version must be semantic version (x.y.z): $version"
        }
        require(description.isNotBlank()) { "@description is required" }
    }

    companion object {
        /** namespace 正则: 小写字母开头 + 小写字母/数字/连字符 */
        val NAMESPACE_REGEX = Regex("^[a-z][a-z0-9-]*$")

        /** 语义版本号正则: x.y.z (x/y/z 为非负整数) */
        val VERSION_REGEX = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    }
}

/**
 * 脚本来源类型。
 */
enum class ScriptSourceType {
    /** 文件选择器导入 */
    FILE,

    /** 粘贴代码导入 */
    PASTE,

    /** URL 拉取导入 */
    URL,
}
