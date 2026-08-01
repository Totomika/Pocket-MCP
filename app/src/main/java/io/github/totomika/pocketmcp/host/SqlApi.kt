package io.github.totomika.pocketmcp.host

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * host.sql API 实现。
 *
 * 每个 namespace 拥有独立的 SQLite 数据库文件 (WAL 模式)。
 * 位于 data 目录: scripts/<namespace>/data/sql/<dbName>.db
 *
 * ```js
 * const db = await host.sql.open("mydb")
 * await db.exec("CREATE TABLE ...")
 * await db.exec("INSERT ...", [args])
 * const rows = await db.query("SELECT ...", [args])
 * await db.transaction(async (tx) => { ... })
 * await db.execMany(["sql1", "sql2"])
 * await db.close()
 * await host.sql.drop("mydb")   // 删除整个数据库文件 (含 WAL/SHM)
 * ```
 *
 * 权限: 自动授予 (docs/03-host-api.md 第 1 层)。
 */
class SqlApi(private val pathManager: FsPathManager) : HostApi {

    override fun inject(quickJs: QuickJs, namespace: String, scope: CoroutineScope) {
        val sqlDir = pathManager.sqlDir(namespace).apply { mkdirs() }
        val connections = mutableMapOf<String, android.database.sqlite.SQLiteDatabase>()

        // open(dbName) → 返回 db handle (字符串 id)
        quickJs.asyncFunction<String>("__sql_open") { args ->
            val dbName = args[0]?.toString() ?: "default"
            val dbFile = File(sqlDir, "$dbName.db")
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            db.enableWriteAheadLogging()
            connections[dbName] = db
            dbName
        }

        // exec(dbName, sql, args?)
        quickJs.asyncFunction<Unit>("__sql_exec") { args ->
            val dbName = args[0]?.toString() ?: ""
            val sql = args[1]?.toString() ?: ""
            val bindArgs = parseBindArgs(args.getOrNull(2))
            val db =
                connections[dbName] ?: throw IllegalStateException("Database not opened: $dbName")
            withContext(Dispatchers.IO) {
                db.execSQL(sql, bindArgs)
            }
        }

        // query(dbName, sql, args?) → JSON 行数组
        quickJs.asyncFunction<String>("__sql_query") { args ->
            val dbName = args[0]?.toString() ?: ""
            val sql = args[1]?.toString() ?: ""
            val bindArgs = parseBindArgs(args.getOrNull(2))
            val db =
                connections[dbName] ?: throw IllegalStateException("Database not opened: $dbName")
            withContext(Dispatchers.IO) {
                val cursor = db.rawQuery(sql, bindArgs)
                cursor.use {
                    val rows = JSONArray()
                    val columnNames = it.columnNames
                    while (it.moveToNext()) {
                        val row = JSONObject()
                        for (i in columnNames.indices) {
                            val value = when (it.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> it.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_BLOB -> it.getBlob(i).toString()
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                else -> it.getString(i)
                            }
                            row.put(columnNames[i], value)
                        }
                        rows.put(row)
                    }
                    rows.toString()
                }
            }
        }

        // execMany(dbName, sqlArray)
        // 注意: quickjs-kt 1.0.5 将 JS Array 映射为 List<Any?>, 不是 Array<*>,
        // 必须同时处理两种类型, 否则 sqlList 为空, 0 条语句执行, 事务提交空操作。
        quickJs.asyncFunction<Unit>("__sql_execMany") { args ->
            val dbName = args[0]?.toString() ?: ""
            val sqlList: List<*> = when (val raw = args[1]) {
                is List<*> -> raw
                is Array<*> -> raw.toList()
                else -> emptyList<Any?>()
            }
            val db =
                connections[dbName] ?: throw IllegalStateException("Database not opened: $dbName")
            withContext(Dispatchers.IO) {
                db.beginTransaction()
                try {
                    for (sqlItem in sqlList) {
                        db.execSQL(sqlItem?.toString() ?: "")
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        // close(dbName)
        quickJs.asyncFunction<Unit>("__sql_close") { args ->
            val dbName = args[0]?.toString() ?: ""
            withContext(Dispatchers.IO) {
                connections.remove(dbName)?.close()
            }
        }

        // drop(dbName) → 关闭连接并删除 .db / .db-wal / .db-shm
        quickJs.asyncFunction<Unit>("__sql_drop") { args ->
            val dbName = args[0]?.toString() ?: ""
            withContext(Dispatchers.IO) {
                connections.remove(dbName)?.close()
                val base = File(sqlDir, "$dbName.db")
                listOf(base, File("$base-wal"), File("$base-shm")).forEach { it.delete() }
            }
        }

        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.sql = {
                  open: async function(dbName) {
                    const id = await __sql_open(dbName);
                    return {
                      _id: id,
                      exec: function(sql, args) { return __sql_exec(id, sql, args); },
                      query: function(sql, args) { return __sql_query(id, sql, args); },
                      execMany: function(sqlList) { return __sql_execMany(id, sqlList); },
                      transaction: async function(fn) {
                        await __sql_exec(id, "BEGIN");
                        try {
                          const tx = {
                            exec: function(sql, args) { return __sql_exec(id, sql, args); },
                            query: function(sql, args) { return __sql_query(id, sql, args); },
                          };
                          await fn(tx);
                          await __sql_exec(id, "COMMIT");
                        } catch (e) {
                          await __sql_exec(id, "ROLLBACK");
                          throw e;
                        }
                      },
                      close: function() { return __sql_close(id); },
                    };
                  },
                  drop: function(dbName) { return __sql_drop(dbName); },
                };
            """.trimIndent()
            )
        }
    }

    /**
     * 解析 JS 传来的绑定参数 → String[]。
     *
     * 注意: quickjs-kt 1.0.5 将 JS Array 映射为 [List]`<Any?>` (不是 Kotlin Array),
     * 因此必须同时处理 List 和 Array 两种情况, 否则 `as? Array<*>` 永远失败,
     * 返回空数组, 导致 SQL `?` 占位符无绑定值 → 全部写入 NULL。
     */
    private fun parseBindArgs(raw: Any?): Array<String> {
        if (raw == null) return emptyArray()
        val items: List<*> = when (raw) {
            is List<*> -> raw
            is Array<*> -> raw.toList()
            else -> return emptyArray()
        }
        return items.map { it?.toString() ?: "" }.toTypedArray()
    }
}
