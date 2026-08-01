package io.github.totomika.pocketmcp

import io.github.totomika.pocketmcp.runtime.RuntimeManager
import io.github.totomika.pocketmcp.runtime.ToolBridge
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 端到端测试: RuntimeManager + ToolBridge 集成。
 *
 * 验证流程:
 * 1. acquire runtime (evaluate 脚本, 注册工具)
 * 2. 工具注册正确
 * 3. ToolBridge.callHandler 能调用 JS handler 并返回 CallToolResult
 *
 * 此测试在 Android 设备上运行 (QuickJs native lib 需要 Android)。
 */
class RuntimeEndToEndTest {

    @Test
    fun runtime_acquires_and_registers_tools() = runBlocking {
        val manager = RuntimeManager()
        val scriptCode = """
            mcp.tool("greet", "Greet someone", {
              type: "object",
              properties: {
                name: { type: "string", description: "Who to greet" }
              },
              required: ["name"]
            }, async (args) => {
              return { content: [{ type: "text", text: "Hello, " + args.name + "!" }] };
            });
        """.trimIndent()

        val entry = manager.acquire("test-ns", scriptCode)

        try {
            // 验证工具已注册
            assertEquals(1, entry.toolRegistry.size)
            val tool = entry.toolRegistry["greet"]
            assertNotNull(tool)
            assertEquals("greet", tool?.localName)
            assertEquals("Greet someone", tool?.description)

            // 验证 ToolBridge 能调用 handler
            val bridge = ToolBridge(manager)
            val args = buildJsonObject {
                put("name", JsonPrimitive("World"))
            }
            val result = bridge.callHandler("test-ns", "greet", args)

            assertNotNull(result)
            assertTrue(result.content.isNotEmpty())
            val firstContent = result.content.first() as TextContent
            assertEquals("Hello, World!", firstContent.text)
            assertEquals(false, result.isError)
        } finally {
            manager.release("test-ns")
        }
    }

    @Test
    fun reference_counting_keeps_runtime_alive_across_multiple_acquires() = runBlocking {
        val manager = RuntimeManager()
        val scriptCode = """
            mcp.tool("ping", "Ping", {}, async () => {
              return { content: [{ type: "text", text: "pong" }] };
            });
        """.trimIndent()

        // 第一次 acquire
        val entry1 = manager.acquire("ref-test", scriptCode)
        assertEquals(1, entry1.refCount)
        assertEquals(1, manager.activeCount())

        // 第二次 acquire (应复用同一 runtime)
        val entry2 = manager.acquire("ref-test", scriptCode)
        assertEquals(entry1, entry2)
        assertEquals(2, entry2.refCount)
        assertEquals(1, manager.activeCount()) // 仍然只有 1 个 runtime

        // 第一次 release (计数减 1, runtime 仍存在)
        manager.release("ref-test")
        assertEquals(1, manager.activeCount())

        // 第二次 release (计数归 0, runtime 销毁)
        manager.release("ref-test")
        assertEquals(0, manager.activeCount())
        assertTrue(entry1.quickJs.isClosed)
    }
}
