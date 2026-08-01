package io.github.totomika.pocketmcp.data.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 日志管理器。
 *
 * 统一日志写入接口, 供 runtime / mcp / permission 等模块使用。
 * 日志存储到 Room DB, 保留最近 7 天。
 *
 * 见 docs/09-ui.md "日志 Tab"。
 *
 * @param dao 日志 DAO
 */
class LogManager(
    private val dao: LogDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 写入日志。
     *
     * 异步写入, 不阻塞调用方。
     */
    fun log(namespace: String, type: LogType, level: LogLevel, message: String) {
        scope.launch {
            dao.insert(
                LogEntry(
                    namespace = namespace,
                    type = type.name,
                    level = level.name,
                    message = message,
                )
            )
        }
    }

    /** 脚本 console 日志 */
    fun console(namespace: String, level: LogLevel, message: String) {
        log(namespace, LogType.CONSOLE, level, message)
    }

    /** MCP 调用日志 */
    fun mcp(namespace: String, message: String) {
        log(namespace, LogType.MCP, LogLevel.INFO, message)
    }

    /** 系统事件日志 */
    fun system(message: String, level: LogLevel = LogLevel.INFO) {
        log(SYSTEM_NAMESPACE, LogType.SYSTEM, level, message)
    }

    /**
     * 观察所有日志 (供 UI 使用)。
     */
    fun observeAll(limit: Int = 500) = dao.observeAll(limit)

    /**
     * 按 namespace 筛选。
     */
    fun observeByNamespace(namespace: String, limit: Int = 500) =
        dao.observeByNamespace(namespace, limit)

    /**
     * 按类型筛选。
     */
    fun observeByType(type: LogType, limit: Int = 500) =
        dao.observeByType(type.name, limit)

    /**
     * 按 namespace + 类型筛选。
     */
    fun observeByNamespaceAndType(namespace: String, type: LogType, limit: Int = 500) =
        dao.observeByNamespaceAndType(namespace, type.name, limit)

    /**
     * 全文搜索。
     */
    fun search(query: String, limit: Int = 500) = dao.search(query, limit)

    /**
     * 清理 7 天前的日志。
     */
    suspend fun cleanupOldLogs() {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dao.deleteOlderThan(sevenDaysAgo)
    }

    /**
     * 删除指定 namespace 的日志 (卸载脚本时)。
     */
    suspend fun deleteByNamespace(namespace: String) {
        dao.deleteByNamespace(namespace)
    }

    /**
     * 清空所有日志。
     */
    suspend fun clearAll() {
        dao.deleteAll()
    }

    /**
     * 清空指定类型的日志。
     */
    suspend fun clearByType(type: LogType) {
        dao.deleteByType(type.name)
    }

    /**
     * 清空匹配搜索关键词的日志。
     */
    suspend fun clearBySearch(query: String) {
        dao.deleteBySearch(query)
    }

    /**
     * 清空指定类型且匹配搜索关键词的日志。
     */
    suspend fun clearByTypeAndSearch(type: LogType, query: String) {
        dao.deleteByTypeAndSearch(type.name, query)
    }

    companion object {
        const val SYSTEM_NAMESPACE = "SYSTEM"
    }
}
