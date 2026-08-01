package io.github.totomika.pocketmcp.host

import android.database.sqlite.SQLiteDatabase
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * host.kv API 实现。
 *
 * 每个 namespace 拥有独立的 KV 存储文件 (per-namespace SQLite)。
 * 位于 data 目录: scripts/<namespace>/data/kv/kvstore.db
 *
 * ```js
 * await host.kv.set(key, value)
 * const v = await host.kv.get(key) // string | null
 * await host.kv.delete(key)
 * const keys = await host.kv.list() // string[]
 * await host.kv.clear()
 * ```
 *
 * 权限: 自动授予 (docs/03-host-api.md 第 1 层)。
 */
class KvApi(private val pathManager: FsPathManager) : HostApi {

    /** per-namespace DB handles, 在 cleanup() 时关闭 */
    private val databases = ConcurrentHashMap<String, SQLiteDatabase>()

    override fun inject(quickJs: QuickJs, namespace: String, scope: CoroutineScope) {
        val db = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val kvDir = pathManager.kvDir(namespace).apply { mkdirs() }
            val dbFile = File(kvDir, "kvstore.db")
            val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            db.execSQL("CREATE TABLE IF NOT EXISTS kv_store (`key` TEXT PRIMARY KEY, `value` TEXT)")
            db
        }
        databases[namespace] = db

        // host.kv.set(key, value)
        quickJs.asyncFunction<Unit>("__kv_set") { args ->
            val key = args[0]?.toString() ?: ""
            val value = args[1]?.toString() ?: ""
            withContext(Dispatchers.IO) {
                db.execSQL(
                    "INSERT OR REPLACE INTO kv_store (`key`, `value`) VALUES (?, ?)",
                    arrayOf(key, value)
                )
            }
        }

        // host.kv.get(key) → string | null
        quickJs.asyncFunction<String?>("__kv_get") { args ->
            val key = args[0]?.toString() ?: ""
            withContext(Dispatchers.IO) {
                val cursor = db.rawQuery(
                    "SELECT `value` FROM kv_store WHERE `key` = ? LIMIT 1",
                    arrayOf(key)
                )
                cursor.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }
        }

        // host.kv.delete(key)
        quickJs.asyncFunction<Unit>("__kv_delete") { args ->
            val key = args[0]?.toString() ?: ""
            withContext(Dispatchers.IO) {
                db.execSQL("DELETE FROM kv_store WHERE `key` = ?", arrayOf(key))
            }
        }

        // host.kv.list() → string[]
        quickJs.asyncFunction<String>("__kv_list") {
            withContext(Dispatchers.IO) {
                val cursor = db.rawQuery("SELECT `key` FROM kv_store ORDER BY `key`", null)
                cursor.use {
                    val keys = mutableListOf<String>()
                    while (it.moveToNext()) {
                        keys.add(it.getString(0))
                    }
                    JSONArray(keys).toString()
                }
            }
        }

        // host.kv.clear()
        quickJs.asyncFunction<Unit>("__kv_clear") {
            withContext(Dispatchers.IO) {
                db.execSQL("DELETE FROM kv_store")
            }
        }

        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.kv = {
                  set: (key, value) => __kv_set(key, value),
                  get: (key) => __kv_get(key),
                  delete: (key) => __kv_delete(key),
                  list: async () => JSON.parse(await __kv_list()),
                  clear: () => __kv_clear(),
                };
            """.trimIndent()
            )
        }
    }

    override fun cleanup(namespace: String) {
        databases.remove(namespace)?.close()
    }
}
