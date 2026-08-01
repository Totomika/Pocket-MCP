package io.github.totomika.pocketmcp.data.sql

/**
 * 脚本数据库浏览器使用的共享数据模型。
 *
 * 纯 Kotlin, 无 Android 依赖, 便于测试。
 */

/** 数据库文件摘要 (单个 namespace 下 sql 目录中的一个 .db 文件)。 */
data class DbFileInfo(
    /** 数据库名称 (不含 .db 后缀, 即脚本调用 host.sql.open 时传入的 name)。 */
    val name: String,
    /** 主数据库文件大小 (字节)。 */
    val sizeBytes: Long,
    /** WAL 文件大小 (字节, 0 表示无 WAL)。 */
    val walSizeBytes: Long,
    /** SHM 文件大小 (字节, 0 表示无 SHM)。 */
    val shmSizeBytes: Long,
) {
    /** 该数据库占用总大小 (含 WAL/SHM)。 */
    val totalSizeBytes: Long get() = sizeBytes + walSizeBytes + shmSizeBytes
}

/** 表摘要。 */
data class TableSummary(
    val name: String,
    /** 列数。 */
    val columnCount: Int,
    /** 行数 (-1 表示获取失败或不可估)。 */
    val rowCount: Long,
)

/** 列信息 (来自 PRAGMA table_info)。 */
data class ColumnInfo(
    val name: String,
    /** 声明类型 (如 "INTEGER", "TEXT", "REAL", "BLOB", 未声明则为 "")。 */
    val type: String,
    /** 是否为主键 (pk > 0)。 */
    val isPrimaryKey: Boolean,
    /** 是否 NOT NULL。 */
    val isNotNull: Boolean,
    /** 是否自增主键 (INTEGER PRIMARY KEY 的别名, 即 rowid 别名)。 */
    val isAutoIncrement: Boolean,
) {
    /** 是否为 BLOB 类型 (类型名包含 BLOB, 或无声明类型时 SQLite 默认行为为 BLOB 亲和)。 */
    val isBlob: Boolean
        get() = type.uppercase().contains("BLOB")

    /** 是否为整数类型 (用于编辑时选择数字键盘)。 */
    val isInteger: Boolean
        get() = type.uppercase() == "INTEGER" || type.uppercase() == "INT"

    /** 是否为浮点类型 (用于编辑时选择小数键盘)。 */
    val isReal: Boolean
        get() = type.uppercase().let {
            it == "REAL" || it == "FLOAT" || it == "DOUBLE" || it == "DECIMAL"
        }
}

/** 排序方向。 */
enum class SortDir {
    /** 默认顺序 (rowid 顺序)。 */
    NONE,
    ASC,
    DESC,
}

/**
 * 一行数据: 列名 → 值。
 *
 * 值类型:
 * - [Long] - INTEGER
 * - [Double] - REAL
 * - [String] - TEXT
 * - [ByteArray] - BLOB
 * - null - NULL
 */
typealias RowData = Map<String, Any?>

/**
 * SQL 执行结果。
 */
sealed interface SqlResult {
    /** SELECT 查询结果。 */
    data class Select(
        val columns: List<String>,
        val rows: List<RowData>,
    ) : SqlResult

    /** 非 SELECT 语句的影响行数。 */
    data class Update(val affectedRows: Int) : SqlResult

    /** 执行错误。 */
    data class Error(val message: String) : SqlResult
}