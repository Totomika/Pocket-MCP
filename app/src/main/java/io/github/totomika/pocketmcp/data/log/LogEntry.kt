package io.github.totomika.pocketmcp.data.log

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日志类型。
 *
 * 见 docs/09-ui.md "三类日志"。
 */
enum class LogType {
    /** 脚本 console.log/info/warn/error */
    CONSOLE,

    /** MCP tools/call 请求 + 响应 */
    MCP,

    /** 系统事件: kill/重启/权限拒绝/服务启停 */
    SYSTEM,
}

/**
 * 日志级别。
 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR,
}

/**
 * 日志实体。
 *
 * - namespace: 脚本 namespace 或 "SYSTEM"
 * - type: 日志类型 (CONSOLE/MCP/SYSTEM)
 * - level: 日志级别
 * - message: 日志内容
 *
 * 保留最近 7 天, 自动清理 (LogDao.deleteOlderThan)。
 *
 * 见 docs/09-ui.md "日志 Tab"。
 */
@Entity(
    tableName = "logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["namespace"]),
        Index(value = ["type"]),
    ],
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val namespace: String,
    val type: String, // LogType.name
    val level: String, // LogLevel.name
    val message: String,
)
