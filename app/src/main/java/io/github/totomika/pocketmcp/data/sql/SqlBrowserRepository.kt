package io.github.totomika.pocketmcp.data.sql

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import java.io.File

/**
 * SQL 数据库浏览器仓库。
 *
 * 直接读写脚本 namespace 下的 SQLite 数据库文件 (与 host.sql 共享同一目录):
 * `files/scripts/<ns>/data/sql/<name>.db` (+ 可选 `<name>.db-wal` / `<name>.db-shm`)。
 *
 * ## 并发策略
 * 每次 op 打开 / 关闭数据库, 不持有长连接, 避免与运行中脚本竞争。
 * 打开后启用 WAL (与 [io.github.totomika.pocketmcp.host.SqlApi] 一致), 提升读并发。
 * 若脚本正在写入且未启用 WAL 的旧库, 浏览器侧启用 WAL 会将文件转为 WAL 模式
 * (SQLite journal_mode 是文件级属性, 对脚本侧连接透明), 这是安全的。
 *
 * ## 标识符安全
 * 所有表名 / 列名在拼接到 SQL 前均通过 [quoteIdent] 双引号转义,
 * 避免注入风险 (表/列名来自 sqlite_master / PRAGMA, 但仍防御性处理)。
 */
class SqlBrowserRepository(private val pathManager: FsPathManager) {

    /**
     * rowid 在 [RowData] 中的内部键名。
     *
     * 仅用于行编辑/删除时唯一定位行, 不会出现在 [ColumnInfo] 列表中 (UI 不展示)。
     * 双下划线前缀避免与真实列名冲突 (SQLite 列名不允许以数字开头, 双下划线也极罕见)。
     */
    val ROWID_KEY: String = "__rowid__"

    // ── 数据库文件级 ──

    /** 列出 namespace 下所有数据库文件 (排除 -wal / -shm 辅助文件)。 */
    fun listDbs(namespace: String): List<DbFileInfo> {
        val sqlDir = pathManager.sqlDir(namespace)
        if (!sqlDir.exists()) return emptyList()
        return sqlDir.listFiles { f -> f.isFile && f.name.endsWith(".db") && !f.name.endsWith("-wal") && !f.name.endsWith("-shm") }
            .orEmpty()
            .sortedBy { it.name }
            .map { f ->
                val wal = File("${f.absolutePath}-wal")
                val shm = File("${f.absolutePath}-shm")
                DbFileInfo(
                    name = f.name.removeSuffix(".db"),
                    sizeBytes = f.length(),
                    walSizeBytes = if (wal.exists()) wal.length() else 0L,
                    shmSizeBytes = if (shm.exists()) shm.length() else 0L,
                )
            }
    }

    /**
     * 删除整个数据库文件 (含 WAL / SHM)。
     *
     * 注意: 不协调运行中脚本。若脚本正持有该库的打开连接 (经 host.sql.open),
     * Android 上文件会被 unlink 但脚本进程的 fd 仍指向私有 inode, 脚本后续写入
     * 会随连接关闭而丢失。UI 侧应在删除前提示用户停止对应脚本。
     */
    fun deleteDb(namespace: String, dbName: String) {
        requireValidDbName(dbName)
        val sqlDir = pathManager.sqlDir(namespace)
        val base = File(sqlDir, "$dbName.db")
        listOf(base, File("$base-wal"), File("$base-shm")).forEach { it.delete() }
    }

    /**
     * 重命名数据库文件 (含 WAL / SHM)。
     *
     * 注意: 与 deleteDb 同理, 不协调运行中脚本。若脚本正持有该库的打开连接,
     * 重命名后脚本仍通过旧 fd 写入原文件, 造成数据不一致。UI 侧应在重命名前提示用户停止对应脚本。
     *
     * @throws IllegalStateException 目标名已存在 (避免静默覆盖)
     * @throws IllegalArgumentException 名称非法 (路径穿越等)
     */
    fun renameDb(namespace: String, oldName: String, newName: String) {
        requireValidDbName(oldName)
        requireValidDbName(newName)
        val sqlDir = pathManager.sqlDir(namespace)
        val oldBase = File(sqlDir, "$oldName.db")
        val newBase = File(sqlDir, "$newName.db")
        check(!newBase.exists()) { "目标数据库 \"$newName\" 已存在" }
        check(oldBase.renameTo(newBase)) { "重命名失败 (源文件不存在或 IO 错误)" }
        File("${oldBase.absolutePath}-wal").let { if (it.exists()) it.renameTo(File("${newBase.absolutePath}-wal")) }
        File("${oldBase.absolutePath}-shm").let { if (it.exists()) it.renameTo(File("${newBase.absolutePath}-shm")) }
    }

    // ── 表级 ──

    /** 列出数据库中的所有表 (跳过 sqlite 内部表, 如 sqlite_sequence)。 */
    fun listTables(namespace: String, dbName: String): List<TableSummary> = withDb(namespace, dbName) { db ->
        val tableNames = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
        tableNames.map { name ->
            val cols = tableInfo(db, name)
            val rowCount = try {
                db.rawQuery("SELECT COUNT(*) FROM ${quoteIdent(name)}", null).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
            } catch (e: Exception) { -1L }
            TableSummary(name = name, columnCount = cols.size, rowCount = rowCount)
        }
    }

    /** 获取表的列信息。 */
    fun columns(namespace: String, dbName: String, table: String): List<ColumnInfo> =
        withDb(namespace, dbName) { db -> tableInfo(db, table) }

    /** 表行数。 */
    fun countRows(namespace: String, dbName: String, table: String): Long =
        withDb(namespace, dbName) { db ->
            db.rawQuery("SELECT COUNT(*) FROM ${quoteIdent(table)}", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }

    /**
     * 分页查询表数据。
     *
     * 非 WITHOUT ROWID 表会额外带出 `rowid`, 存入每行 [RowData] 的 [ROWID_KEY] 键下,
     * 作为行编辑/删除的唯一标识 (UI 不展示该键, 仅用于定位)。
     *
     * @param sortCol 排序列名 (null 表示默认 rowid 顺序, 仅对有 rowid 的表有效)
     * @param sortDir 排序方向 ([SortDir.NONE] 视为默认顺序)
     */
    fun pageRows(
        namespace: String,
        dbName: String,
        table: String,
        sortCol: String?,
        sortDir: SortDir,
        offset: Int,
        limit: Int,
    ): Pair<List<ColumnInfo>, List<RowData>> = withDb(namespace, dbName) { db ->
        val cols = tableInfo(db, table)
        val colNames = cols.map { it.name }

        // 检测表是否有隐式 rowid (WITHOUT ROWID 表没有, SELECT rowid 会抛异常)。
        val hasRowid = try {
            db.rawQuery("SELECT rowid FROM ${quoteIdent(table)} LIMIT 1", null).use { c ->
                c.moveToFirst()
            }
        } catch (e: Exception) { false }

        val selectCols = if (hasRowid) "rowid, *" else "*"
        val orderBy = buildOrderBy(sortCol, sortDir, hasRowid)
        val sql = "SELECT $selectCols FROM ${quoteIdent(table)}$orderBy LIMIT ? OFFSET ?"
        val args = arrayOf(limit.toString(), offset.toString())
        val rows = try {
            db.rawQuery(sql, args).use { c ->
                if (hasRowid) cursorToRowsWithRowid(c, colNames)
                else cursorToRows(c, colNames)
            }
        } catch (e: Exception) {
            // WITHOUT ROWID 表 ORDER BY rowid 会失败, 回退到无排序重试
            if (sortCol == null || sortDir == SortDir.NONE) {
                val fallback = "SELECT $selectCols FROM ${quoteIdent(table)} LIMIT ? OFFSET ?"
                db.rawQuery(fallback, args).use { c ->
                    if (hasRowid) cursorToRowsWithRowid(c, colNames)
                    else cursorToRows(c, colNames)
                }
            } else throw e
        }
        cols to rows
    }

    // ── 行编辑 ──

    /**
     * 更新行 (按 [where] 中的所有列 AND 定位)。
     *
     * [where] 支持多列复合定位 (如复合主键), 避免单列不唯一时误伤多行。
     *
     * @param updates 列名 → 新值 (String / Long / Double / ByteArray / null)
     * @return 受影响行数
     */
    fun updateRow(
        namespace: String,
        dbName: String,
        table: String,
        where: Map<String, Any?>,
        updates: Map<String, Any?>,
    ): Int = withDb(namespace, dbName) { db -> db.writeTx {
        val setClause = updates.keys.joinToString(", ") { "${quoteIdent(it)} = ?" }
        val whereClause = where.keys.joinToString(" AND ") { "${quoteIdent(it)} = ?" }
        val sql = "UPDATE ${quoteIdent(table)} SET $setClause WHERE $whereClause"
        val bindArgs = (updates.values.toList() + where.values.toList())
            .map { toBindArg(it) }.toTypedArray()
        db.execSQL(sql, bindArgs)
        changes(db)
    }}

    /**
     * 插入一行。
     *
     * @param values 列名 → 值 (省略的列由 SQLite 默认值 / 自增处理)
     * @return 受影响行数 (通常为 1)
     */
    fun insertRow(
        namespace: String,
        dbName: String,
        table: String,
        values: Map<String, Any?>,
    ): Int = withDb(namespace, dbName) { db -> db.writeTx {
        if (values.isEmpty()) {
            db.execSQL("INSERT INTO ${quoteIdent(table)} DEFAULT VALUES")
        } else {
            val cols = values.keys.joinToString(", ") { quoteIdent(it) }
            val placeholders = values.keys.joinToString(", ") { "?" }
            val sql = "INSERT INTO ${quoteIdent(table)} ($cols) VALUES ($placeholders)"
            val bindArgs = values.values.map { toBindArg(it) }.toTypedArray()
            db.execSQL(sql, bindArgs)
        }
        changes(db)
    }}

    /**
     * 删除行 (按 [where] 中的所有列 AND 定位)。
     * @return 受影响行数
     */
    fun deleteRow(
        namespace: String,
        dbName: String,
        table: String,
        where: Map<String, Any?>,
    ): Int = withDb(namespace, dbName) { db -> db.writeTx {
        val whereClause = where.keys.joinToString(" AND ") { "${quoteIdent(it)} = ?" }
        val sql = "DELETE FROM ${quoteIdent(table)} WHERE $whereClause"
        val bindArgs = where.values.map { toBindArg(it) }.toTypedArray()
        db.execSQL(sql, bindArgs)
        changes(db)
    }}

    // ── 表管理 ──

    /** 删除整张表。 */
    fun deleteTable(namespace: String, dbName: String, table: String) =
        withDb(namespace, dbName) { db ->
            db.execSQL("DROP TABLE ${quoteIdent(table)}")
        }

    /** 重命名表 (ALTER TABLE ... RENAME TO ...)。 */
    fun renameTable(namespace: String, dbName: String, oldName: String, newName: String) =
        withDb(namespace, dbName) { db ->
            db.execSQL("ALTER TABLE ${quoteIdent(oldName)} RENAME TO ${quoteIdent(newName)}")
        }

    // ── SQL 控制台 ──

    /**
     * 执行任意 SQL。
     *
     * - SELECT / WITH / PRAGMA / VALUES → [SqlResult.Select]
     * - 其他 (INSERT / UPDATE / DELETE / DDL) → [SqlResult.Update]
     * - 执行异常 → [SqlResult.Error]
     *
     * 多语句支持: 非 SELECT SQL 使用 execSQL (sqlite3_exec 支持分号分隔多语句),
     * 但 changes() 仅反映最后一条语句的影响行数。
     */
    fun exec(namespace: String, dbName: String, sql: String): SqlResult = withDb(namespace, dbName) { db ->
        try {
            if (isQuerySql(sql)) {
                db.rawQuery(sql, null).use { c ->
                    val colNames = c.columnNames.toList()
                    val rows = cursorToRows(c, colNames)
                    SqlResult.Select(columns = colNames, rows = rows)
                }
            } else {
                // 事务内执行: WAL 模式下 execSQL 走主连接, 但 SELECT changes() 会被路由到
                // 只读连接池导致返回 0; 事务将两者锁定到同一 (主) 连接, 确保 changes() 正确。
                db.writeTx {
                    db.execSQL(sql)
                    SqlResult.Update(affectedRows = changes(db))
                }
            }
        } catch (e: Exception) {
            SqlResult.Error(message = e.message ?: e.javaClass.simpleName)
        }
    }

    // ── 内部工具 ──

    /**
     * 在事务内执行写操作, 确保 `execSQL` 与随后的 `SELECT changes()` 走同一 (主) 连接。
     *
     * WAL 模式下, [SQLiteDatabase] 使用连接池: 写语句走主连接, 只读语句 (如
     * `SELECT changes()`) 被路由到只读连接池, 导致 `changes()` 返回 0。事务将所有
     * 操作锁定到主连接, 修复此问题。
     */
    private inline fun <T> SQLiteDatabase.writeTx(block: (SQLiteDatabase) -> T): T {
        beginTransactionNonExclusive()
        try {
            val result = block(this)
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }

    private inline fun <T> withDb(namespace: String, dbName: String, block: (SQLiteDatabase) -> T): T {
        requireValidDbName(dbName)
        val sqlDir = pathManager.sqlDir(namespace).apply { mkdirs() }
        val dbFile = File(sqlDir, "$dbName.db")
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        return db.use {
            it.enableWriteAheadLogging()
            block(it)
        }
    }

    /** PRAGMA table_info → [ColumnInfo] 列表。 */
    private fun tableInfo(db: SQLiteDatabase, table: String): List<ColumnInfo> {
        // PRAGMA table_info 本身不支持绑定参数, 但表名来自 sqlite_master, 安全可信;
        // 仍做防御性转义: PRAGMA 不接受双引号标识符, 仅接受方括号或裸名, 这里用裸名并拒绝可疑字符。
        val safeName = table.replace("\"", "").replace(";", "").trim()
        return db.rawQuery("PRAGMA table_info(\"$safeName\")", null).use { c ->
            val out = ArrayList<ColumnInfo>()
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val type = c.getString(c.getColumnIndexOrThrow("type")) ?: ""
                val notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1
                val pk = c.getInt(c.getColumnIndexOrThrow("pk"))
                val isAutoIncrement = pk > 0 && type.uppercase() == "INTEGER"
                out.add(ColumnInfo(
                    name = name,
                    type = type,
                    isPrimaryKey = pk > 0,
                    isNotNull = notNull,
                    isAutoIncrement = isAutoIncrement,
                ))
            }
            out
        }
    }

    /** 构建 ORDER BY 子句 (含前导空格, 或空字符串)。 */
    private fun buildOrderBy(sortCol: String?, sortDir: SortDir, hasRowid: Boolean): String {
        if (sortCol == null || sortDir == SortDir.NONE) {
            // 仅对有 rowid 的表使用 rowid 默认排序; WITHOUT ROWID 表无 rowid, 不排序
            return if (hasRowid) " ORDER BY rowid" else ""
        }
        val dir = if (sortDir == SortDir.ASC) "ASC" else "DESC"
        return " ORDER BY ${quoteIdent(sortCol)} $dir"
    }

    /** 标识符双引号转义 (SQLite 标准), 内部 " → ""。 */
    private fun quoteIdent(name: String): String = "\"${name.replace("\"", "\"\"")}\""

    /**
     * 校验数据库名称合法性, 防止路径穿越等沙箱逃逸。
     *
     * 禁止: 空白、路径分隔符 (`/` `\`)、目录穿越 (`..`)。
     */
    private fun requireValidDbName(name: String) {
        require(name.isNotBlank()) { "数据库名称不能为空" }
        require(!name.contains('/') && !name.contains('\\')) { "数据库名称不能包含路径分隔符" }
        require(!name.contains("..")) { "数据库名称不能包含 \"..\"" }
    }

    /** 读取游标所有行, 按列类型映射值。 */
    private fun cursorToRows(c: Cursor, colNames: List<String>): List<RowData> {
        val rows = ArrayList<RowData>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            val row = LinkedHashMap<String, Any?>(colNames.size)
            for (i in colNames.indices) {
                row[colNames[i]] = readCursorValue(c, i)
            }
            rows.add(row)
        }
        return rows
    }

    /**
     * 读取游标所有行, 第 0 列为 rowid (Long), 存入 [ROWID_KEY];
     * 其余列 (1..n) 按 [colNames] 顺序映射。
     */
    private fun cursorToRowsWithRowid(c: Cursor, colNames: List<String>): List<RowData> {
        val rows = ArrayList<RowData>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            val row = LinkedHashMap<String, Any?>(colNames.size + 1)
            row[ROWID_KEY] = c.getLong(0)
            for (i in colNames.indices) {
                row[colNames[i]] = readCursorValue(c, i + 1)
            }
            rows.add(row)
        }
        return rows
    }

    /** 读取游标指定列的值, 按类型映射。 */
    private fun readCursorValue(c: Cursor, index: Int): Any? = when (c.getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(index)
        Cursor.FIELD_TYPE_NULL -> null
        else -> c.getString(index)
    }

    /** 绑定参数值转换为 execSQL 接受的 [Array<Any?>] 元素类型。 */
    private fun toBindArg(v: Any?): Any? = when (v) {
        is ByteArray -> v
        is Long, is Int, is Double, is Float, is String -> v
        null -> null
        else -> v.toString()
    }

    /** 获取上一条语句的影响行数。 */
    private fun changes(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT changes()", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** 判断 SQL 是否为返回结果集的语句 (SELECT / WITH / PRAGMA / VALUES)。 */
    private fun isQuerySql(sql: String): Boolean {
        var s = sql.trimStart()
        // 跳过前导注释
        while (true) {
            if (s.startsWith("/*")) {
                val end = s.indexOf("*/", 2)
                if (end < 0) break
                s = s.substring(end + 2).trimStart()
            } else if (s.startsWith("--")) {
                val nl = s.indexOf('\n')
                if (nl < 0) break
                s = s.substring(nl + 1).trimStart()
            } else break
        }
        val first = s.takeWhile { it.isLetterOrDigit() || it == '_' }.uppercase()
        return first == "SELECT" || first == "WITH" || first == "PRAGMA" || first == "VALUES"
    }
}