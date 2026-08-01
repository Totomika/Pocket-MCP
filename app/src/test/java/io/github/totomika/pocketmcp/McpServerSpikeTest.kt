package io.github.totomika.pocketmcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL

/**
 * Spike 3: 验证 Kotlin MCP SDK mcpStreamableHttp() 在 CIO 引擎上能跑通。
 *
 * 测试流程:
 * 1. 创建 MCP Server, 注册硬编码 tool
 * 2. 用 Ktor CIO 启动 server on 127.0.0.1:随机端口
 * 3. 发 initialize, 提取 session ID
 * 4. 发 notifications/initialized
 * 5. 发 tools/list, 验证返回注册的 tool
 * 6. 发 tools/call, 验证返回 tool 结果
 *
 * 此测试在 JVM 上运行 (MCP SDK + Ktor CIO 均兼容 JVM)。
 */
class McpServerSpikeTest {

    @Test
    fun `MCP server responds to initialize tools_list and tools_call`() = runBlocking {
        val port = findFreePort()
        val server = createTestMcpServer()

        val ktorServer = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            mcpStreamableHttp(path = "/mcp") {
                server
            }
        }.start(wait = false)

        try {
            val baseUrl = "http://127.0.0.1:$port/mcp"

            // 1. Initialize
            val (initResponse, sessionId) = sendMcpRequest(
                baseUrl,
                sessionId = null,
                json = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-05","capabilities":{},"clientInfo":{"name":"spike-test","version":"1.0"}}}"""
            )
            val initJson = Json.parseToJsonElement(initResponse).jsonObject
            val initResult = initJson["result"]!!.jsonObject
            assertEquals(
                "android-mcp-spike",
                initResult["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content
            )
            assertNotNull("Should have session ID", sessionId)

            // 2. notifications/initialized (no response expected, HTTP 202)
            sendMcpNotification(
                baseUrl,
                sessionId = sessionId,
                json = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
            )

            // 3. tools/list
            val (listResponse, _) = sendMcpRequest(
                baseUrl,
                sessionId = sessionId,
                json = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
            )
            val listJson = Json.parseToJsonElement(listResponse).jsonObject
            val tools = listJson["result"]!!.jsonObject["tools"]!!.jsonArray
            assertTrue("Should have at least 1 tool", tools.isNotEmpty())
            val tool = tools[0].jsonObject
            assertEquals("echo", tool["name"]!!.jsonPrimitive.content)

            // 4. tools/call
            val (callResponse, _) = sendMcpRequest(
                baseUrl,
                sessionId = sessionId,
                json = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hello"}}}"""
            )
            val callJson = Json.parseToJsonElement(callResponse).jsonObject
            val callResult = callJson["result"]!!.jsonObject
            val content = callResult["content"]!!.jsonArray
            assertTrue("Should have content", content.isNotEmpty())
            val text = content[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue("Should echo back", text.contains("hello"))
        } finally {
            ktorServer.stop(1000, 2000)
        }
    }

    /**
     * 创建最小 MCP Server, 注册一个 echo tool。
     */
    private fun createTestMcpServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "android-mcp-spike",
                version = "1.0.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        server.addTool(
            name = "echo",
            description = "Echoes back the provided message",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("message", buildJsonObject {
                        put("type", "string")
                        put("description", "Message to echo back")
                    })
                },
                required = listOf("message")
            )
        ) { request ->
            val message = request.arguments
                ?.get("message")?.jsonPrimitive?.content ?: "no message"
            CallToolResult(
                content = listOf(TextContent("Echo: $message"))
            )
        }

        return server
    }

    /**
     * 发送 MCP JSON-RPC notification (无 id, 无响应体)。
     * 服务器应返回 202 Accepted。
     */
    private fun sendMcpNotification(
        baseUrl: String,
        sessionId: String?,
        json: String
    ) {
        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (sessionId != null) {
                setRequestProperty("Mcp-Session-Id", sessionId)
            }
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        conn.outputStream.use { os: OutputStream ->
            os.write(json.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        assertTrue("Notification HTTP should be 202, got $responseCode", responseCode == 202)
        conn.disconnect()
    }

    /**
     * 发送 MCP JSON-RPC 请求, 返回 (响应体, sessionId)。
     * sessionId 从响应头 Mcp-Session-Id 提取 (initialize 时有)。
     */
    private fun sendMcpRequest(
        baseUrl: String,
        sessionId: String?,
        json: String
    ): Pair<String, String?> {
        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (sessionId != null) {
                setRequestProperty("Mcp-Session-Id", sessionId)
            }
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        conn.outputStream.use { os: OutputStream ->
            os.write(json.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        assertTrue("HTTP should be 200, got $responseCode", responseCode == 200)

        val body = BufferedReader(
            InputStreamReader(conn.inputStream, Charsets.UTF_8)
        ).use { it.readText() }

        val newSessionId = conn.getHeaderField("Mcp-Session-Id")

        // MCP Streamable HTTP 可能返回 SSE 格式或 JSON。
        // 尝试从 SSE 格式提取 JSON (data: {...})
        val jsonBody = extractJsonFromResponse(body)

        conn.disconnect()
        return jsonBody to newSessionId
    }

    /**
     * 从响应体提取 JSON。
     * MCP Streamable HTTP 可能返回:
     * - 纯 JSON: {"jsonrpc":"2.0",...}
     * - SSE 格式: event: message\ndata: {"jsonrpc":"2.0",...}
     */
    private fun extractJsonFromResponse(body: String): String {
        val trimmed = body.trim()
        // Try plain JSON first
        if (trimmed.startsWith("{")) {
            return trimmed
        }
        // Try SSE format: extract data: lines
        val dataLines = trimmed.lines()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ").trim() }
        if (dataLines.isNotEmpty()) {
            return dataLines.first()
        }
        // Fallback: return as-is
        return body
    }

    private fun findFreePort(): Int {
        return ServerSocket().use { socket ->
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            socket.localPort
        }
    }
}
