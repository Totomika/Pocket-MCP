package io.github.totomika.pocketmcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import io.github.totomika.pocketmcp.host.FsPermissionChecker
import io.github.totomika.pocketmcp.host.HostApiRegistry
import io.github.totomika.pocketmcp.runtime.RuntimeManager
import io.github.totomika.pocketmcp.runtime.ToolBridge
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 端到端测试: host.kv + host.fs.private 集成。
 *
 * 验证脚本能通过 host.* API 读写数据。
 */
class HostApiEndToEndTest {

    @Test
    fun host_kv_set_and_get() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = HostApiRegistry(
            HostApiRegistry.createDefault(
                pathManager = FsPathManager(context),
                context = context,
                fsPermissionChecker = AlwaysGrantedFsPermissionChecker,
            )
        )
        val manager = RuntimeManager(
            runtimeFactory = io.github.totomika.pocketmcp.runtime.RuntimeFactory(
                hostApiRegistry = registry,
            ),
        )

        val scriptCode = """
            mcp.tool("set_and_get", "Set and get a KV value", {
              type: "object",
              properties: {
                key: { type: "string" },
                value: { type: "string" }
              },
              required: ["key", "value"]
            }, async (args) => {
              await host.kv.set(args.key, args.value);
              const retrieved = await host.kv.get(args.key);
              return { content: [{ type: "text", text: "got: " + retrieved }] };
            });
        """.trimIndent()

        val entry = manager.acquire("kv-test", scriptCode)
        try {
            val bridge = ToolBridge(manager)
            val args = buildJsonObject {
                put("key", JsonPrimitive("greeting"))
                put("value", JsonPrimitive("hello world"))
            }
            val result = bridge.callHandler("kv-test", "set_and_get", args)

            assertTrue(result.content.isNotEmpty())
            val text = (result.content.first() as TextContent).text
            assertTrue("Should contain retrieved value: $text", text.contains("hello world"))
        } finally {
            manager.release("kv-test")
        }
    }

    @Test
    fun host_fs_private_write_and_read() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = HostApiRegistry(
            HostApiRegistry.createDefault(
                pathManager = FsPathManager(context),
                context = context,
                fsPermissionChecker = AlwaysGrantedFsPermissionChecker,
            )
        )
        val manager = RuntimeManager(
            runtimeFactory = io.github.totomika.pocketmcp.runtime.RuntimeFactory(
                hostApiRegistry = registry,
            ),
        )

        val scriptCode = """
            mcp.tool("write_read", "Write and read a file", {
              type: "object",
              properties: {
                filename: { type: "string" },
                content: { type: "string" }
              },
              required: ["filename", "content"]
            }, async (args) => {
              await host.fs.private.write(args.filename, args.content);
              const read = await host.fs.private.read(args.filename);
              return { content: [{ type: "text", text: read }] };
            });
        """.trimIndent()

        val entry = manager.acquire("fs-test", scriptCode)
        try {
            val bridge = ToolBridge(manager)
            val args = buildJsonObject {
                put("filename", JsonPrimitive("test.txt"))
                put("content", JsonPrimitive("file content here"))
            }
            val result = bridge.callHandler("fs-test", "write_read", args)

            assertTrue(result.content.isNotEmpty())
            val text = (result.content.first() as TextContent).text
            assertEquals("file content here", text)
        } finally {
            manager.release("fs-test")
        }
    }
}

/**
 * 测试用全授予权限 stub: 让 [HostApiRegistry.createDefault] 的 fs.shared 注入通过。
 *
 * 这些 E2E 测试只测 host.kv / host.fs.private, 不实际走 fs.shared 的权限拦截,
 * 但 [io.github.totomika.pocketmcp.host.FsApi.injectShared] 在注入阶段就硬要求非空 checker,
 * 所以这里喂一个 no-op stub 让注入放行。prod 用 [io.github.totomika.pocketmcp.permission.ScriptPermissionChecker]。
 */
private object AlwaysGrantedFsPermissionChecker : FsPermissionChecker {
    override fun checkRead(namespace: String, path: String) {}
    override fun checkWrite(namespace: String, path: String) {}
}
