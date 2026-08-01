package io.github.totomika.pocketmcp.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScriptMetadataParser 单元测试。
 */
class ScriptMetadataParserTest {

    @Test
    fun parse_complete_metadata() {
        val source = """
            // @name Memory
            // @namespace memory
            // @version 1.0.0
            // @author Alice
            // @description Persistent key-value memory system
            // @instructions When the user asks to remember or recall info.
            //   Use the read tool to get by key, write tool to store.
            //   Unknown keys return "(empty)".
            // @homepage https://github.com/alice/memory-mcp
            // @minAppVersion 1.0

            mcp.tool("read", "Read", {}, async () => {});
        """.trimIndent()

        val meta = ScriptMetadataParser.parse(source)
        assertNotNull(meta)
        assertEquals("Memory", meta!!.name)
        assertEquals("memory", meta.namespace)
        assertEquals("1.0.0", meta.version)
        assertEquals("Persistent key-value memory system", meta.description)
        assertEquals("Alice", meta.author)
        assertTrue(meta.instructions!!.contains("Use the read tool"))
        assertTrue(meta.instructions!!.contains("Unknown keys return"))
        assertEquals("https://github.com/alice/memory-mcp", meta.homepage)
        assertEquals("1.0", meta.minAppVersion)
    }

    @Test
    fun parse_minimal_required_fields() {
        val source = """
            // @name Test
            // @namespace test
            // @version 1.0.0
            // @description A test script

            mcp.tool("x", "x", {}, async () => {});
        """.trimIndent()

        val meta = ScriptMetadataParser.parse(source)
        assertNotNull(meta)
        assertEquals("Test", meta!!.name)
        assertEquals("test", meta.namespace)
        assertNull(meta.author)
        assertNull(meta.instructions)
    }

    @Test
    fun parse_missing_required_field_returns_null() {
        val source = """
            // @name Test
            // @namespace test
            // @version 1.0.0

            mcp.tool("x", "x", {}, async () => {});
        """.trimIndent()

        val meta = ScriptMetadataParser.parse(source)
        assertNull(meta) // 缺 @description
    }

    @Test
    fun parse_empty_source_returns_null() {
        val meta = ScriptMetadataParser.parse("")
        assertNull(meta)
    }

    @Test
    fun parse_multiline_instructions() {
        val source = """
            // @name Test
            // @namespace test
            // @version 1.0.0
            // @description Test
            // @instructions Line 1
            //   Line 2
            //   Line 3

            mcp.tool("x", "x", {}, async () => {});
        """.trimIndent()

        val meta = ScriptMetadataParser.parse(source)
        assertNotNull(meta)
        val instructions = meta!!.instructions!!
        assertTrue(instructions.contains("Line 1"))
        assertTrue(instructions.contains("Line 2"))
        assertTrue(instructions.contains("Line 3"))
    }

    @Test
    fun parse_stops_at_first_non_comment_line() {
        // 元数据在代码之后, 不应被解析
        val source = """
            // @name Test
            // @namespace test
            // @version 1.0.0
            // @description Test

            mcp.tool("x", "x", {}, async () => {});
            // @name ShouldNotBeParsed
        """.trimIndent()

        val meta = ScriptMetadataParser.parse(source)
        assertEquals("Test", meta!!.name)
    }

    @Test
    fun validate_valid_namespace() {
        val meta = ScriptMetadata(
            name = "Test", namespace = "memory", version = "1.0.0", description = "Test"
        )
        meta.validate() // should not throw
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_invalid_namespace_uppercase() {
        ScriptMetadata(
            name = "Test", namespace = "Memory", version = "1.0.0", description = "Test"
        ).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_invalid_namespace_underscore() {
        ScriptMetadata(
            name = "Test", namespace = "my_script", version = "1.0.0", description = "Test"
        ).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun validate_invalid_version_format() {
        ScriptMetadata(
            name = "Test", namespace = "test", version = "1.0", description = "Test"
        ).validate()
    }

    @Test
    fun validate_namespace_with_hyphen_and_digits() {
        ScriptMetadata(
            name = "Test", namespace = "note-manager2", version = "1.0.0", description = "Test"
        ).validate()
    }
}

/**
 * VersionUtils 单元测试。
 */
class VersionUtilsTest {

    @Test
    fun compare_same_versions() {
        assertEquals(0, VersionUtils.compare("1.0.0", "1.0.0"))
        assertTrue(VersionUtils.isSame("2.3.1", "2.3.1"))
    }

    @Test
    fun compare_newer_major() {
        assertTrue(VersionUtils.isNewer("2.0.0", "1.0.0"))
        assertFalse(VersionUtils.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun compare_newer_minor() {
        assertTrue(VersionUtils.isNewer("1.2.0", "1.1.0"))
        assertTrue(VersionUtils.isNewer("1.1.5", "1.1.0"))
    }

    @Test
    fun compare_newer_patch() {
        assertTrue(VersionUtils.isNewer("1.0.1", "1.0.0"))
        assertTrue(VersionUtils.isOlder("1.0.0", "1.0.1"))
    }

    @Test
    fun compare_complex_versions() {
        assertTrue(VersionUtils.isNewer("3.1.2", "3.0.9"))
        assertTrue(VersionUtils.isNewer("10.0.0", "9.99.99"))
    }

    @Test
    fun parse_invalid_version() {
        assertNull(VersionUtils.parse("1.0"))
    }

    @Test
    fun parse_non_numeric_version() {
        assertNull(VersionUtils.parse("1.0.a"))
    }
}
