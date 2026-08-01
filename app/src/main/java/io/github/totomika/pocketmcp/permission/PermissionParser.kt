package io.github.totomika.pocketmcp.permission

/**
 * 解析脚本头部注释中的 @permission 声明。
 *
 * 格式: `// @permission <token>[:<spec>]`
 *
 * 示例 (注意: glob 用 STAR STAR 表示递归, 避免在 KDoc 中误触发注释):
 * ```
 * // @permission host.fs.shared.read:~/Download/STARSTAR
 * // @permission host.fs.shared.write:~/Documents/STAR
 * // @permission host.fetch
 * ```
 *
 * 只解析脚本头部连续注释块 (遇到非注释行停止)。
 * 见 docs/02-script-api.md "头部注释元数据"。
 */
object PermissionParser {

    private val PERMISSION_LINE_REGEX = Regex(
        """^\s*//\s*@permission\s+([^:\s]+)(?::(\S+))?\s*$"""
    )

    /**
     * 从脚本源码解析权限声明。
     *
     * 只扫描头部注释区域: 从第一行开始, 跳过空行和注释行,
     * 遇到第一个非注释、非空行时停止。
     *
     * @return 声明列表 (可能为空), 未识别的 token 会被跳过
     */
    fun parse(source: String): List<PermissionDeclaration> {
        val declarations = mutableListOf<PermissionDeclaration>()
        val lines = source.lines()

        for (line in lines) {
            val trimmed = line.trim()

            // 空行: 在头部区域内允许, 继续
            if (trimmed.isEmpty()) continue

            // 注释行: 检查是否为 @permission
            if (trimmed.startsWith("//")) {
                val match = PERMISSION_LINE_REGEX.find(line)
                if (match != null) {
                    val (tokenRaw, specRaw) = match.destructured
                    val token = PermissionToken.fromString(tokenRaw)
                    if (token != null) {
                        val spec = specRaw.takeIf { it.isNotEmpty() }
                        // 校验: 需要 spec 的 token 必须有 spec
                        if (token.requiresSpec && spec == null) {
                            // 声明不完整, 跳过
                            continue
                        }
                        declarations.add(PermissionDeclaration(token, spec))
                    }
                    // 未识别的 token (如 host.notification) 静默跳过
                }
                // 其他注释行 (非 @permission): 继续
                continue
            }

            // 非注释、非空行: 头部区域结束
            break
        }

        return declarations
    }

    /**
     * 解析并返回人类可读的权限描述列表 (供 UI 使用)。
     */
    fun parseForDisplay(source: String): List<PermissionDisplay> =
        parse(source).map { PermissionDisplay.from(it) }
}
