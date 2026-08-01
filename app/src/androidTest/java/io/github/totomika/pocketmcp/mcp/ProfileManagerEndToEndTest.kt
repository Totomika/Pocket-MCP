package io.github.totomika.pocketmcp.mcp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.totomika.pocketmcp.data.db.AppDatabase
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import io.github.totomika.pocketmcp.runtime.RuntimeManager
import io.github.totomika.pocketmcp.runtime.ToolBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL

/**
 * M5 端到端测试: ServiceManager + MCP 协议全流程。
 *
 * 验证:
 * 1. 创建 Service, 启动服务
 * 2. MCP 客户端 initialize → tools/list → tools/call
 * 3. 多 Service 不同端口
 * 4. 脚本添加后 tools/list_changed (工具列表变化)
 *
 * 测试脚本: 简单 echo 工具, 不依赖 host.* API。
 */
class ProfileManagerEndToEndTest {

    private lateinit var db: AppDatabase
    private lateinit var serviceManager: ServiceManager
    private lateinit var runtimeManager: RuntimeManager
    private lateinit var pathManager: FsPathManager
    private lateinit var serviceManifestStore: ServiceManifestStore

    private val echoScript = """
        mcp.tool("echo", "Echo back the message", {
          type: "object",
          properties: {
            message: { type: "string", description: "Message to echo" }
          },
          required: ["message"]
        }, async (args) => {
          return { content: [{ type: "text", text: "Echo: " + args.message }] };
        });
    """.trimIndent()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        pathManager = FsPathManager(context)
        serviceManifestStore = ServiceManifestStore(pathManager)

        runtimeManager = RuntimeManager()
        val toolBridge = ToolBridge(runtimeManager)
        val factory = McpServerFactory(runtimeManager, toolBridge)
        serviceManager = ServiceManager(
            manifestStore = serviceManifestStore,
            portManager = PortManager(),
            runtimeManager = runtimeManager,
            serverFactory = factory,
        )
    }

    @After
    fun teardown() {
        runBlocking {
            serviceManager.destroyAll()
        }
        db.close()
    }

    @Test
    fun start_service_and_call_tool_via_mcp_protocol() = runBlocking {
        // 1. 创建 Service
        val port = findFreePort()
        val service = serviceManager.createService("test", port = port)
        serviceManager.registerScriptCode("echo-ns", echoScript)
        serviceManager.addScriptToService(service.id, "echo-ns", echoScript)

        // 2. 启动服务
        serviceManager.startService(service.id)
        assertEquals(1, serviceManager.activeServiceCount())

        // 3. MCP 客户端: initialize → tools/list → tools/call
        val baseUrl = "http://127.0.0.1:$port/mcp"

        // initialize
        val (initResp, sessionId) = sendMcpRequest(
            baseUrl, null,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""
        )
        val initResult = Json.parseToJsonElement(initResp).jsonObject["result"]!!.jsonObject
        assertTrue(
            "Server name should contain service name",
            initResult["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content.contains("test")
        )
        assertNotNull("Should have session ID", sessionId)

        // notifications/initialized
        sendMcpNotification(
            baseUrl, sessionId,
            """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )

        // tools/list
        val (listResp, _) = sendMcpRequest(
            baseUrl, sessionId,
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        )
        val tools =
            Json.parseToJsonElement(listResp).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertTrue("Should have at least 1 tool", tools.isNotEmpty())
        val toolName = tools[0].jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("echo-ns.echo", toolName)

        // tools/call
        val (callResp, _) = sendMcpRequest(
            baseUrl, sessionId,
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo-ns.echo","arguments":{"message":"hello"}}}"""
        )
        val callResult = Json.parseToJsonElement(callResp).jsonObject["result"]!!.jsonObject
        val content = callResult["content"]!!.jsonArray
        assertTrue("Should have content", content.isNotEmpty())
        val text = content[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Should echo back", text.contains("hello"))
    }

    @Test
    fun multiple_services_on_different_ports() = runBlocking {
        val port1 = findFreePort()
        val port2 = findFreePort()

        val service1 = serviceManager.createService("svc1", port = port1)
        val service2 = serviceManager.createService("svc2", port = port2)

        serviceManager.registerScriptCode("ns1", echoScript)
        serviceManager.registerScriptCode("ns2", echoScript)

        serviceManager.addScriptToService(service1.id, "ns1", echoScript)
        serviceManager.addScriptToService(service2.id, "ns2", echoScript)

        serviceManager.startService(service1.id)
        serviceManager.startService(service2.id)
        assertEquals(2, serviceManager.activeServiceCount())

        // 两个服务都能响应 tools/list
        val tools1 = listTools(port1)
        val tools2 = listTools(port2)
        assertTrue("Service 1 should have tools", tools1.isNotEmpty())
        assertTrue("Service 2 should have tools", tools2.isNotEmpty())
        assertEquals("ns1.echo", tools1[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("ns2.echo", tools2[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun add_script_updates_tool_list() = runBlocking {
        val port = findFreePort()
        val service = serviceManager.createService("dynamic", port = port)
        serviceManager.startService(service.id)

        // 初始无工具
        var tools = listTools(port)
        assertTrue("Should have no tools initially", tools.isEmpty())

        // 添加脚本
        serviceManager.registerScriptCode("dyn-ns", echoScript)
        serviceManager.addScriptToService(service.id, "dyn-ns", echoScript)

        // 工具列表更新
        tools = listTools(port)
        assertTrue("Should have 1 tool after adding script", tools.isNotEmpty())
        assertEquals("dyn-ns.echo", tools[0].jsonObject["name"]!!.jsonPrimitive.content)

        // 移除脚本
        serviceManager.removeScriptFromService(service.id, "dyn-ns")
        tools = listTools(port)
        assertTrue("Should have no tools after removing script", tools.isEmpty())
    }

    @Test
    fun stop_service_releases_runtime() = runBlocking {
        val port = findFreePort()
        val service = serviceManager.createService("stop-test", port = port)
        serviceManager.registerScriptCode("stop-ns", echoScript)
        serviceManager.addScriptToService(service.id, "stop-ns", echoScript)

        serviceManager.startService(service.id)
        assertEquals(1, serviceManager.activeServiceCount())
        assertEquals(1, runtimeManager.activeCount())

        // 停止服务
        serviceManager.stopService(service.id)
        assertEquals(0, serviceManager.activeServiceCount())
        assertEquals(0, runtimeManager.activeCount())
    }

    // region MCP HTTP 客户端辅助

    private suspend fun listTools(port: Int): List<JsonObject> {
        val baseUrl = "http://127.0.0.1:$port/mcp"
        val (_, sessionId) = sendMcpRequest(
            baseUrl, null,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
        )
        sendMcpNotification(
            baseUrl, sessionId,
            """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )
        val (listResp, _) = sendMcpRequest(
            baseUrl, sessionId,
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        )
        val result = Json.parseToJsonElement(listResp).jsonObject["result"]!!.jsonObject
        return result["tools"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    private fun sendMcpRequest(
        baseUrl: String, sessionId: String?, json: String,
    ): Pair<String, String?> {
        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (sessionId != null) setRequestProperty("Mcp-Session-Id", sessionId)
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        conn.outputStream.use { os: OutputStream -> os.write(json.toByteArray()) }
        val code = conn.responseCode
        assertTrue("HTTP 200 expected, got $code", code == 200)
        val body = BufferedReader(
            InputStreamReader(
                conn.inputStream,
                Charsets.UTF_8
            )
        ).use { it.readText() }
        val newSessionId = conn.getHeaderField("Mcp-Session-Id")
        val jsonBody = extractJson(body)
        conn.disconnect()
        return jsonBody to newSessionId
    }

    private fun sendMcpNotification(baseUrl: String, sessionId: String?, json: String) {
        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (sessionId != null) setRequestProperty("Mcp-Session-Id", sessionId)
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        conn.outputStream.use { os: OutputStream -> os.write(json.toByteArray()) }
        assertTrue("Notification should return 202", conn.responseCode == 202)
        conn.disconnect()
    }

    private fun extractJson(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return trimmed
        val dataLines = trimmed.lines().filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ").trim() }
        if (dataLines.isNotEmpty()) return dataLines.first()
        return body
    }

    private fun findFreePort(): Int = ServerSocket().use { socket ->
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        socket.localPort
    }

    // endregion
}
