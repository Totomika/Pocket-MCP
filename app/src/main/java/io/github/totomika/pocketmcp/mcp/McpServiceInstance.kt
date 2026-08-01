package io.github.totomika.pocketmcp.mcp

import io.ktor.server.engine.EmbeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * 一个运行中的 MCP 服务实例。
 *
 * 封装:
 * - service: 配置信息 (内存读模 [ServiceEntry])
 * - mcpServer: SDK Server 实例 (管理工具注册、session)
 * - ktorServer: Ktor HTTP server (监听端口)
 * - registeredTools: 已注册的工具全名集合 (`namespace_toolName`)
 * - scriptNamespaces: 该服务引用的脚本 namespace 集合 (用于 release runtime)
 */
class McpServiceInstance(
    val service: ServiceEntry,
    val mcpServer: Server,
    val ktorServer: EmbeddedServer<*, *>,
    val registeredTools: MutableSet<String>,
    val scriptNamespaces: MutableSet<String>,
) {
    /**
     * 停止服务: 停止 Ktor server。
     *
     * 注意: runtime 的引用计数释放由 ServiceManager 负责
     * (因为它持有 RuntimeManager 引用)。
     */
    fun stop() {
        ktorServer.stop(GRACE_PERIOD, TIMEOUT)
    }

    /**
     * 通知所有活跃 session: 工具列表已变化。
     *
     * SDK 的 sendToolListChanged(sessionId) 是 per-session 的,
     * 需遍历所有活跃 session 发送通知。
     */
    suspend fun notifyToolsListChanged() {
        for (sessionId in mcpServer.sessions.keys) {
            mcpServer.sendToolListChanged(sessionId)
        }
    }

    companion object {
        /** Ktor 停止的优雅等待时间 (ms) */
        private const val GRACE_PERIOD = 1000L

        /** Ktor 停止的超时时间 (ms) */
        private const val TIMEOUT = 2000L
    }
}
