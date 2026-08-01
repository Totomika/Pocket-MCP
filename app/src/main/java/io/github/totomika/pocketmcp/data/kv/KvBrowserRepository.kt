package io.github.totomika.pocketmcp.data.kv

import android.database.sqlite.SQLiteDatabase
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import io.github.totomika.pocketmcp.data.sql.SortDir
import java.io.File

/**
 * KV 存储浏览器仓库。
 *
 * 直接读写脚本 namespace 下的 KV SQLite 文件 (与 host.kv 共享同一文件):
 * `files/scripts/<ns>/data/kv/kvstore.db`, 表 `kv_store(key TEXT PRIMARY KEY, value TEXT)`。
 *
 * ## 并发策略
 * 每次 op 打开 / 关闭数据库, 不持有长连接, 避免与运行中脚本的 [io.github.totomika.pocketmcp.host.KvApi]
 * 连接竞争。若脚本正在写入, 读取可能抛 [android.database.sqlite.SQLiteDatabaseLockedException],
 * 调用方应捕获并映射为 UI 错误态。
 *
 * 与 KvApi 保持一致: 不启用 WAL (保持文件模式与脚本侧相同)。
 */
class KvBrowserRepository(private val pathManager: FsPathManager) {

    /** KV 条目总数。 */
    fun count(namespace: String): Int = withDb(namespace) { db ->
        db.rawQuery("SELECT COUNT(*) FROM kv_store", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * 分页查询键值对。
     *
     * @param offset 偏移量 (0-based)
     * @param limit 每页条数
     * @param sortDir 按 key 排序方向 ([SortDir.NONE] 视为 ASC, 因为无默认物理顺序时 key 升序最直观)
     * @param keyFilter key 过滤子串 (大小写不敏感, 为空表示不过滤)
     */
    fun page(
        namespace: String,
        offset: Int,
        limit: Int,
        sortDir: SortDir,
        keyFilter: String,
    ): List<Pair<String, String>> = withDb(namespace) { db ->
        val orderBy = when (sortDir) {
            SortDir.DESC -> "ORDER BY `key` DESC"
            SortDir.NONE, SortDir.ASC -> "ORDER BY `key` ASC"
        }
        val where = if (keyFilter.isNotBlank()) {
            "WHERE `key` LIKE '%' || ? || '%' ESCAPE '\\'"
        } else ""
        val bindArgs = if (keyFilter.isNotBlank()) arrayOf(escapeLike(keyFilter)) else null
        val sql = "SELECT `key`, `value` FROM kv_store $where $orderBy LIMIT ? OFFSET ?"
        val args = if (bindArgs != null) {
            arrayOf(*bindArgs, limit.toString(), offset.toString())
        } else {
            arrayOf(limit.toString(), offset.toString())
        }
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<Pair<String, String>>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                out.add(c.getString(0) to (c.getString(1) ?: ""))
            }
            out
        }
    }

    /** 读取单个 key 的值 (不存在返回 null)。 */
    fun get(namespace: String, key: String): String? = withDb(namespace) { db ->
        db.rawQuery(
            "SELECT `value` FROM kv_store WHERE `key` = ? LIMIT 1",
            arrayOf(key)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    /** 写入 / 覆盖一个键值对。 */
    fun set(namespace: String, key: String, value: String) = withDb(namespace) { db ->
        db.execSQL(
            "INSERT OR REPLACE INTO kv_store (`key`, `value`) VALUES (?, ?)",
            arrayOf<Any>(key, value)
        )
    }

    /** 删除一个键。 */
    fun delete(namespace: String, key: String) = withDb(namespace) { db ->
        db.execSQL("DELETE FROM kv_store WHERE `key` = ?", arrayOf<Any>(key))
    }

    /** 清空全部键值对。 */
    fun clear(namespace: String) = withDb(namespace) { db ->
        db.execSQL("DELETE FROM kv_store")
    }

    // ── 内部工具 ──

    /** 打开 KV 数据库并执行 [block], 完成后关闭。 */
    private inline fun <T> withDb(namespace: String, block: (SQLiteDatabase) -> T): T {
        val kvDir = pathManager.kvDir(namespace).apply { mkdirs() }
        val dbFile = File(kvDir, "kvstore.db")
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        return db.use {
            // 确保表存在 (与 KvApi 一致, 幂等)
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS kv_store (`key` TEXT PRIMARY KEY, `value` TEXT)"
            )
            block(it)
        }
    }

    /** 转义 LIKE 模式中的特殊字符 (\, %, _), 使用 ESCAPE '\\' 语法。 */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}