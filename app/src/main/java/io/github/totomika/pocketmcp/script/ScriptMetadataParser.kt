package io.github.totomika.pocketmcp.script

/**
 * 解析脚本头部注释元数据。
 *
 * 格式: `// @field value`
 *
 * 只解析脚本头部连续注释区域 (遇到非注释行停止)。
 * 多行字段 (@instructions): 续行用 `//   ` (两个空格缩进)。
 *
 * 见 docs/02-script-api.md "头部注释元数据"。
 * 见 docs/08-distribution.md "元数据格式"。
 */
object ScriptMetadataParser {

    private val FIELD_REGEX = Regex("""^\s*//\s*@(\w+)\s+(.*)$""")
    private val CONTINUATION_REGEX = Regex("""^\s*//\s+(.*)$""")
    private val NAMESPACE_REGEX = ScriptMetadata.NAMESPACE_REGEX
    private val VERSION_REGEX = ScriptMetadata.VERSION_REGEX

    /**
     * 从脚本源码解析元数据。
     *
     * @param source 脚本源码
     * @return 解析到的元数据, 若必填字段缺失返回 null
     */
    fun parse(source: String): ScriptMetadata? {
        val fields = mutableMapOf<String, StringBuilder>()
        val multiLineFields = setOf("instructions") // 支持多行的字段

        var lastField: String? = null
        val lines = source.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // 空行: 在头部区域内允许, 结束多行字段
            if (trimmed.isEmpty()) {
                lastField = null
                continue
            }

            // 注释行
            if (trimmed.startsWith("//")) {
                val fieldMatch = FIELD_REGEX.find(line)
                if (fieldMatch != null) {
                    val (fieldName, value) = fieldMatch.destructured
                    fields[fieldName] = StringBuilder(value)
                    lastField = if (fieldName in multiLineFields) fieldName else null
                    continue
                }

                // 多行续行
                val contMatch = CONTINUATION_REGEX.find(line)
                if (contMatch != null && lastField != null) {
                    val (value) = contMatch.destructured
                    fields[lastField]!!.append("\n").append(value)
                    continue
                }

                // 其他注释行: 结束多行字段
                lastField = null
                continue
            }

            // 非注释、非空行: 头部区域结束
            break
        }

        // 构造 ScriptMetadata
        val name = fields["name"]?.toString()?.trim()
        val namespace = fields["namespace"]?.toString()?.trim()
        val version = fields["version"]?.toString()?.trim()
        val description = fields["description"]?.toString()?.trim()

        // 必填字段校验
        if (name.isNullOrEmpty() || namespace.isNullOrEmpty() ||
            version.isNullOrEmpty() || description.isNullOrEmpty()
        ) {
            return null
        }

        return ScriptMetadata(
            name = name,
            namespace = namespace,
            version = version,
            description = description,
            author = fields["author"]?.toString()?.trim()?.ifEmpty { null },
            instructions = fields["instructions"]?.toString()?.trim()?.ifEmpty { null },
            homepage = fields["homepage"]?.toString()?.trim()?.ifEmpty { null },
            minAppVersion = fields["minAppVersion"]?.toString()?.trim()?.ifEmpty { null },
        )
    }

    /**
     * 解析并校验元数据。
     *
     * @throws IllegalArgumentException 校验失败
     */
    fun parseAndValidate(source: String): ScriptMetadata {
        val metadata = parse(source)
            ?: throw IllegalArgumentException("Missing required metadata fields")
        metadata.validate()
        return metadata
    }
}
