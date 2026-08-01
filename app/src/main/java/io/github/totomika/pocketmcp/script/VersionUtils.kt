package io.github.totomika.pocketmcp.script

/**
 * 语义版本号对比工具。
 *
 * 格式: major.minor.patch (如 1.0.0, 2.1.3)
 *
 * 见 docs/08-distribution.md "更新检测"。
 */
object VersionUtils {

    /**
     * 解析语义版本号为三元组 (major, minor, patch)。
     *
     * @return (major, minor, patch), 解析失败返回 null
     */
    fun parse(version: String): Triple<Int, Int, Int>? {
        val parts = version.split(".")
        if (parts.size != 3) return null
        return try {
            Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * 对比两个版本号。
     *
     * @return 正数 = v1 更新, 负数 = v2 更新, 0 = 相同
     */
    fun compare(v1: String, v2: String): Int {
        val p1 = parse(v1) ?: throw IllegalArgumentException("Invalid version: $v1")
        val p2 = parse(v2) ?: throw IllegalArgumentException("Invalid version: $v2")

        if (p1.first != p2.first) return p1.first - p2.first
        if (p1.second != p2.second) return p1.second - p2.second
        return p1.third - p2.third
    }

    /**
     * v1 > v2 ?
     */
    fun isNewer(v1: String, v2: String): Boolean = compare(v1, v2) > 0

    /**
     * v1 == v2 ?
     */
    fun isSame(v1: String, v2: String): Boolean = compare(v1, v2) == 0

    /**
     * v1 < v2 ?
     */
    fun isOlder(v1: String, v2: String): Boolean = compare(v1, v2) < 0
}
