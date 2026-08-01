package io.github.totomika.pocketmcp.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PermissionParser 单元测试。
 *
 * 验证头部注释 @permission 声明的解析。
 */
class PermissionParserTest {

    @Test
    fun parse_empty_source_returns_empty() {
        val result = PermissionParser.parse("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun parse_no_permission_returns_empty() {
        val source = """
            // @name Test
            // @description No permissions
            mcp.tool("x", "x", {}, async () => {});
        """.trimIndent()
        val result = PermissionParser.parse(source)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parse_single_fetch_permission() {
        val source = """
            // @name Test
            // @permission host.fetch

            mcp.tool("x", "x", {}, async () => {});
        """.trimIndent()
        val result = PermissionParser.parse(source)
        assertEquals(1, result.size)
        assertEquals(PermissionToken.FETCH, result[0].token)
        assertEquals(null, result[0].spec)
    }

    @Test
    fun parse_fs_shared_read_with_glob_spec() {
        val source = """
            // @name Test
            // @permission host.fs.shared.read:~/Download/**
        """.trimIndent()
        val result = PermissionParser.parse(source)
        assertEquals(1, result.size)
        assertEquals(PermissionToken.FS_SHARED_READ, result[0].token)
        assertEquals("~/Download/**", result[0].spec)
    }

    @Test
    fun parse_multiple_permissions() {
        val source = """
            // @name Memory
            // @namespace memory
            // @version 1.0.0
            //
            // @permission host.fs.shared.read:~/Download/**
            // @permission host.fs.shared.write:~/Documents/*
            // @permission host.fetch
            // @permission host.clipboard
            // @permission host.toast

            mcp.tool("read", "Read", {}, async () => {});
        """.trimIndent()
        val result = PermissionParser.parse(source)
        assertEquals(5, result.size)
        assertEquals(PermissionToken.FS_SHARED_READ, result[0].token)
        assertEquals("~/Download/**", result[0].spec)
        assertEquals(PermissionToken.FS_SHARED_WRITE, result[1].token)
        assertEquals("~/Documents/*", result[1].spec)
        assertEquals(PermissionToken.FETCH, result[2].token)
        assertEquals(PermissionToken.CLIPBOARD, result[3].token)
        assertEquals(PermissionToken.TOAST, result[4].token)
    }

    @Test
    fun parse_stops_at_first_non_comment_line() {
        // 权限声明在代码之后, 不应被解析
        val source = """
            // @name Test

            mcp.tool("x", "x", {}, async () => {});
            // @permission host.fetch
        """.trimIndent()
        val result = PermissionParser.parse(source)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parse_skips_unknown_token() {
        val source = """
            // @name Test
            // @permission host.notification
            // @permission host.fetch
        """.trimIndent()
        val result = PermissionParser.parse(source)
        assertEquals(1, result.size)
        assertEquals(PermissionToken.FETCH, result[0].token)
    }

    @Test
    fun parse_fs_shared_without_spec_is_skipped() {
        val source = """
            // @name Test
            // @permission host.fs.shared.read
        """.trimIndent()
        val result = PermissionParser.parse(source)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parse_all_tokens() {
        val source = """
            // @permission host.fs.shared.read:~/Download/**
            // @permission host.fs.shared.write:~/Documents/*
            // @permission host.fetch
            // @permission host.clipboard
            // @permission host.deviceInfo
            // @permission host.toast
            // @permission host.openUrl
        """.trimIndent()
        val result = PermissionParser.parse(source)
        assertEquals(7, result.size)
    }
}
