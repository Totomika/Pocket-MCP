package io.github.totomika.pocketmcp.ui.navigation

import android.net.Uri

/**
 * 导航路由。
 *
 * 见 docs/09-ui.md "导航结构"。
 */
object Routes {
    const val SCRIPTS = "scripts"
    const val SERVICES = "services"
    const val LOGS = "logs"
    const val ADD_SCRIPT = "add_script"
    const val GUIDE = "guide"

    const val SCRIPT_DETAIL = "script_detail/{namespace}"
    fun scriptDetail(namespace: String) = "script_detail/$namespace"

    const val SERVICE_DETAIL = "service_detail/{id}"
    fun serviceDetail(id: String) = "service_detail/$id"

    // ── 数据管理 (脚本运行时数据浏览器) ──

    /** 数据管理概览页: KV / SQL 两张摘要卡片。 */
    const val DATA_OVERVIEW = "data_overview/{namespace}"
    fun dataOverview(namespace: String) = "data_overview/${Uri.encode(namespace)}"

    /** KV 存储管理页。 */
    const val DATA_KV = "data_kv/{namespace}"
    fun dataKv(namespace: String) = "data_kv/${Uri.encode(namespace)}"

    /** SQL 数据库列表页。 */
    const val DATA_SQL = "data_sql/{namespace}"
    fun dataSql(namespace: String) = "data_sql/${Uri.encode(namespace)}"

    /** 单个数据库详情页 (表列表 + 控制台入口)。 */
    const val DATA_SQL_DB = "data_sql_db/{namespace}/{dbName}"
    fun dataSqlDb(namespace: String, dbName: String) =
        "data_sql_db/${Uri.encode(namespace)}/${Uri.encode(dbName)}"

    /** 单张表的数据视图页。 */
    const val DATA_SQL_TABLE = "data_sql_table/{namespace}/{dbName}/{tableName}"
    fun dataSqlTable(namespace: String, dbName: String, tableName: String) =
        "data_sql_table/${Uri.encode(namespace)}/${Uri.encode(dbName)}/${Uri.encode(tableName)}"

    /** SQL 控制台页。 */
    const val DATA_SQL_CONSOLE = "data_sql_console/{namespace}/{dbName}"
    fun dataSqlConsole(namespace: String, dbName: String) =
        "data_sql_console/${Uri.encode(namespace)}/${Uri.encode(dbName)}"
}
