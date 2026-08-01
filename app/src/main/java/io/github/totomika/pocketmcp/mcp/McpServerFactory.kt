package io.github.totomika.pocketmcp.mcp

import io.github.totomika.pocketmcp.runtime.RuntimeManager
import io.github.totomika.pocketmcp.runtime.ToolBridge
import io.github.totomika.pocketmcp.runtime.ToolDefinition
import io.github.totomika.pocketmcp.script.RuntimeConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * MCP Server 工厂。
 *
 * 创建 SDK Server + Ktor server, 注册工具, 设置 instructions。
 *
 * 工具命名: namespace_toolName (如 "memory_read"), 避免冲突。
 * 使用下划线而非点号, 因为 MCP 规范要求工具名匹配 ^[a-zA-Z0-9_-]+$。
 *
 * @param runtimeManager 查找 runtime
 * @param toolBridge 桥接 JS handler
 */
class McpServerFactory(
    private val runtimeManager: RuntimeManager,
    private val toolBridge: ToolBridge,
) {

    /**
     * 创建并启动一个 MCP 服务实例。
     *
     * @param service 服务配置
     * @param scriptRefs 该服务包含的脚本引用列表 (namespace + enabled)
     * @param scriptCodes namespace → 脚本源码 (用于 acquire runtime)
     * @param runtimeConfigs namespace → RuntimeConfig (用于 acquire runtime)
     * @param instructions 拼接的 @instructions (可为空)
     * @return McpServiceInstance
     */
    suspend fun create(
        service: ServiceEntry,
        scriptRefs: List<ServiceManifest.ScriptRef>,
        scriptCodes: Map<String, String>,
        runtimeConfigs: Map<String, RuntimeConfig?> = emptyMap(),
        instructions: String?,
    ): McpServiceInstance {
        // 1. 创建 SDK Server (用 instructions 构造器, 支持 instructions 传给客户端)
        val capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = true),
            logging = ServerCapabilities.Logging,
        )
        val mcpServer = if (instructions.isNullOrEmpty()) {
            Server(
                serverInfo = Implementation(
                    name = "pocketmcp-${service.name}",
                    version = "1.0.0",
                ),
                options = ServerOptions(capabilities = capabilities),
            )
        } else {
            Server(
                serverInfo = Implementation(
                    name = "pocketmcp-${service.name}",
                    version = "1.0.0",
                ),
                options = ServerOptions(capabilities = capabilities),
                instructions = instructions,
            )
        }

        // 2. 为每个启用的脚本注册工具
        val registeredTools = mutableSetOf<String>() // "namespace_toolName"
        for (ref in scriptRefs) {
            if (!ref.enabled) continue

            val code = scriptCodes[ref.namespace]
            if (code == null) continue

            // 确保 runtime 存在 (引用计数 +1)
            runtimeManager.acquire(ref.namespace, code, runtimeConfigs[ref.namespace])

            val runtime = runtimeManager.getRuntime(ref.namespace)
            if (runtime != null) {
                for ((localName, toolDef) in runtime.toolRegistry) {
                    val fullName = "${ref.namespace}_$localName"
                    registerTool(mcpServer, fullName, toolDef, ref.namespace, localName)
                    registeredTools.add(fullName)
                }
            }
        }

        // 3. 启动 Ktor server (仅 localhost)
        val ktorServer = embeddedServer(CIO, host = "127.0.0.1", port = service.port) {
            mcpStreamableHttp(path = "/mcp") {
                mcpServer
            }
        }.start(wait = false)

        return McpServiceInstance(
            service = service,
            mcpServer = mcpServer,
            ktorServer = ktorServer,
            registeredTools = registeredTools,
            scriptNamespaces = scriptRefs.filter { it.enabled }.map { it.namespace }.toMutableSet(),
        )
    }

    /**
     * 注册单个工具到 MCP Server。
     *
     * public 供 ServiceManager.rebuildTools 复用。
     */
    fun registerTool(
        mcpServer: Server,
        fullName: String,
        toolDef: ToolDefinition,
        namespace: String,
        localName: String,
    ) {
        val schemaJson = try {
            Json.parseToJsonElement(toolDef.inputSchemaJson) as JsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }

        val properties = if (schemaJson.containsKey("properties")) {
            schemaJson["properties"] as JsonObject
        } else {
            JsonObject(emptyMap())
        }

        val required = if (schemaJson.containsKey("required")) {
            (schemaJson["required"] as? JsonArray)
                ?.map { it.toString().trim('"') }
                ?: emptyList()
        } else {
            emptyList()
        }

        mcpServer.addTool(
            name = fullName,
            description = toolDef.description,
            inputSchema = ToolSchema(
                properties = properties,
                required = required,
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            toolBridge.callHandler(namespace, localName, args)
        }
    }
}
