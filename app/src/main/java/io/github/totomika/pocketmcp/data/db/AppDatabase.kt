package io.github.totomika.pocketmcp.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.totomika.pocketmcp.data.log.LogDao
import io.github.totomika.pocketmcp.data.log.LogEntry

/**
 * App 主数据库。
 *
 * 重构后只剩一张表: `LogEntry` (跨脚本全局日志)。
 * 其它已迁出至文件化 (per-namespace/per-service manifest):
 * - 脚本清单 → `files/scripts/<ns>/manifest.json` (见 ScriptManifest)
 * - 服务清单 → `files/services/<svcId>/manifest.json` (见 ServiceManifest)
 *
 * host.kv / host.sql 的 per-namespace 数据库仍各自独立文件。
 */
@Database(
    entities = [LogEntry::class],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}
