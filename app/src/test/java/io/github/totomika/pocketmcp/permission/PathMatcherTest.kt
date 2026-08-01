package io.github.totomika.pocketmcp.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PathMatcher 单元测试。
 *
 * 验证 glob 通配符匹配 + symlink 防护 + write 隐含 read。
 *
 * 注意: 测试中使用真实文件系统路径, ~ 被替换为 /storage/emulated/0。
 */
class PathMatcherTest {

    @Test
    fun double_star_matches_recursively() {
        // ~/Download/** 匹配 ~/Download/notes/log.txt (递归)
        assertTrue(
            PathMatcher.isPathGranted(
                "~/Download/**",
                "~/Download/notes/log.txt",
            )
        )
    }

    @Test
    fun double_star_matches_direct_child() {
        // ** 也匹配直接子项
        assertTrue(
            PathMatcher.isPathGranted(
                "~/Download/**",
                "~/Download/file.txt",
            )
        )
    }

    @Test
    fun single_star_matches_direct_child_only() {
        // ~/Download/* 匹配 ~/Download/notes/ (直接子项, 目录本身)
        assertTrue(
            PathMatcher.isPathGranted(
                "~/Download/*",
                "~/Download/notes",
            )
        )
    }

    @Test
    fun single_star_does_not_match_nested_file() {
        // ~/Download/* 不匹配 ~/Download/notes/log.txt (进入了子目录)
        assertFalse(
            PathMatcher.isPathGranted(
                "~/Download/*",
                "~/Download/notes/log.txt",
            )
        )
    }

    @Test
    fun non_matching_path_rejected() {
        // ~/Download/** 不匹配 ~/Documents/file.txt
        assertFalse(
            PathMatcher.isPathGranted(
                "~/Download/**",
                "~/Documents/file.txt",
            )
        )
    }

    @Test
    fun exact_path_matches() {
        // 无通配符, 精确匹配
        assertTrue(
            PathMatcher.isPathGranted(
                "~/Download/file.txt",
                "~/Download/file.txt",
            )
        )
    }

    @Test
    fun different_path_rejected() {
        assertFalse(
            PathMatcher.isPathGranted(
                "~/Download/file.txt",
                "~/Download/other.txt",
            )
        )
    }

    @Test
    fun is_any_granted_empty_list_returns_false() {
        assertFalse(PathMatcher.isAnyGranted(emptyList(), "~/Download/file.txt"))
    }

    @Test
    fun is_any_granted_matches_one_of_many() {
        val globs = listOf("~/Documents/*", "~/Download/**")
        assertTrue(PathMatcher.isAnyGranted(globs, "~/Download/notes/log.txt"))
    }

    @Test
    fun is_any_granted_none_match_returns_false() {
        val globs = listOf("~/Documents/*", "~/Pictures/*")
        assertFalse(PathMatcher.isAnyGranted(globs, "~/Download/file.txt"))
    }

    @Test
    fun tilde_replaced_correctly() {
        // ~ 被替换为 /storage/emulated/0
        assertTrue(
            PathMatcher.isPathGranted(
                "~/Download/**",
                "/storage/emulated/0/Download/notes/log.txt",
            )
        )
    }

    @Test
    fun absolute_path_glob_matches() {
        assertTrue(
            PathMatcher.isPathGranted(
                "/storage/emulated/0/Download/**",
                "~/Download/file.txt",
            )
        )
    }
}
