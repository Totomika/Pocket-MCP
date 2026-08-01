package io.github.totomika.pocketmcp.data.log

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 日志数据访问。
 *
 * 支持按 namespace / type 筛选, 全文搜索, 7 天自动清理。
 */
@Dao
interface LogDao {

    @Insert
    suspend fun insert(log: LogEntry): Long

    @Insert
    suspend fun insertAll(logs: List<LogEntry>): List<Long>

    /**
     * 获取所有日志 (按时间倒序)。
     */
    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeAll(limit: Int = 500): Flow<List<LogEntry>>

    /**
     * 按 namespace 筛选。
     */
    @Query("SELECT * FROM logs WHERE namespace = :namespace ORDER BY timestamp DESC LIMIT :limit")
    fun observeByNamespace(namespace: String, limit: Int = 500): Flow<List<LogEntry>>

    /**
     * 按类型筛选。
     */
    @Query("SELECT * FROM logs WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    fun observeByType(type: String, limit: Int = 500): Flow<List<LogEntry>>

    /**
     * 按 namespace + 类型筛选。
     */
    @Query("SELECT * FROM logs WHERE namespace = :namespace AND type = :type ORDER BY timestamp DESC LIMIT :limit")
    fun observeByNamespaceAndType(
        namespace: String,
        type: String,
        limit: Int = 500
    ): Flow<List<LogEntry>>

    /**
     * 全文搜索。
     */
    @Query("SELECT * FROM logs WHERE message LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    fun search(query: String, limit: Int = 500): Flow<List<LogEntry>>

    /**
     * 删除指定时间之前的日志 (7 天清理)。
     */
    @Query("DELETE FROM logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    /**
     * 删除所有日志。
     */
    @Query("DELETE FROM logs")
    suspend fun deleteAll()

    /**
     * 删除指定 namespace 的日志。
     */
    @Query("DELETE FROM logs WHERE namespace = :namespace")
    suspend fun deleteByNamespace(namespace: String)

    /**
     * 删除指定类型的日志。
     */
    @Query("DELETE FROM logs WHERE type = :type")
    suspend fun deleteByType(type: String)

    /**
     * 删除匹配搜索关键词的日志。
     */
    @Query("DELETE FROM logs WHERE message LIKE '%' || :query || '%'")
    suspend fun deleteBySearch(query: String)

    /**
     * 删除指定类型且匹配搜索关键词的日志。
     */
    @Query("DELETE FROM logs WHERE type = :type AND message LIKE '%' || :query || '%'")
    suspend fun deleteByTypeAndSearch(type: String, query: String)
}
