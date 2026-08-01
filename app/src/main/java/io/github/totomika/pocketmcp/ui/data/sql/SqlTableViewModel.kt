package io.github.totomika.pocketmcp.ui.data.sql

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.data.sql.ColumnInfo
import io.github.totomika.pocketmcp.data.sql.RowData
import io.github.totomika.pocketmcp.data.sql.SortDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SqlTableUiState(
    val columns: List<ColumnInfo> = emptyList(),
    val rows: List<RowData> = emptyList(),
    val totalCount: Long = 0,
    val page: Int = 0,
    val pageSize: Int = 50,
    val totalPages: Int = 0,
    val sortCol: String? = null,
    val sortDir: SortDir = SortDir.NONE,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class SqlTableViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = app.container.sqlBrowserRepository

    private val _uiState = MutableStateFlow(SqlTableUiState())
    val uiState: StateFlow<SqlTableUiState> = _uiState.asStateFlow()

    private var namespace: String = ""
    private var dbName: String = ""
    private var tableName: String = ""

    fun load(namespace: String, dbName: String, tableName: String) {
        this.namespace = namespace
        this.dbName = dbName
        this.tableName = tableName
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val columns = repository.columns(namespace, dbName, tableName)
                val totalCount = repository.countRows(namespace, dbName, tableName)
                val pageSize = _uiState.value.pageSize
                val (_, rows) = repository.pageRows(
                    namespace, dbName, tableName,
                    sortCol = null,
                    sortDir = SortDir.NONE,
                    offset = 0,
                    limit = pageSize,
                )
                val totalPages = if (totalCount == 0L) 0 else ((totalCount + pageSize - 1) / pageSize).toInt()
                _uiState.value = SqlTableUiState(
                    columns = columns,
                    rows = rows,
                    totalCount = totalCount,
                    page = 0,
                    pageSize = pageSize,
                    totalPages = totalPages,
                    sortCol = null,
                    sortDir = SortDir.NONE,
                    loading = false,
                    error = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = e.message,
                )
            }
        }
    }

    fun loadPage(page: Int) {
        val ns = namespace
        val db = dbName
        val table = tableName
        if (ns.isEmpty() || db.isEmpty() || table.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            doLoadPage(page)
        }
    }

    private suspend fun doLoadPage(page: Int) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        try {
            val state = _uiState.value
            val offset = page * state.pageSize
            val (_, rows) = repository.pageRows(
                namespace, dbName, tableName,
                sortCol = state.sortCol,
                sortDir = state.sortDir,
                offset = offset,
                limit = state.pageSize,
            )
            _uiState.value = state.copy(
                rows = rows,
                page = page,
                loading = false,
                error = null,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                loading = false,
                error = e.message,
            )
        }
    }

    fun setSort(col: String) {
        val current = _uiState.value
        val newSortDir = when {
            current.sortCol != col -> SortDir.ASC
            current.sortDir == SortDir.NONE -> SortDir.ASC
            current.sortDir == SortDir.ASC -> SortDir.DESC
            else -> SortDir.NONE
        }
        val newSortCol = if (newSortDir == SortDir.NONE) null else col
        _uiState.value = current.copy(sortCol = newSortCol, sortDir = newSortDir, page = 0)
        viewModelScope.launch(Dispatchers.IO) {
            doLoadPage(0)
        }
    }

    fun nextPage() {
        val current = _uiState.value
        if (current.page + 1 < current.totalPages) {
            loadPage(current.page + 1)
        }
    }

    fun prevPage() {
        val current = _uiState.value
        if (current.page > 0) {
            loadPage(current.page - 1)
        }
    }

    /**
     * 确定用于唯一定位行的标识列集合。
     *
     * 优先级:
     * 1. rowid (页面查询时已带出, 存于 [SqlBrowserRepository.ROWID_KEY]) —
     *    覆盖所有非 WITHOUT ROWID 表, 即使无主键也能唯一标识。
     * 2. 所有主键列 (复合主键时取全部 PK 列, AND 定位) —
     *    覆盖 WITHOUT ROWID 表 (必须有声明的主键)。
     * 3. 返回 null — 无 rowid 且无主键, 无法唯一确定, 拒绝编辑/删除。
     */
    private fun resolveIdentity(row: RowData): Map<String, Any?>? {
        // 1. rowid (优先, 覆盖绝大多数表)
        row[repository.ROWID_KEY]?.let { rowid ->
            return mapOf("rowid" to rowid)
        }
        // 2. 所有主键列 (AND 定位, 覆盖复合主键 / WITHOUT ROWID 表)
        val cols = _uiState.value.columns
        val pkCols = cols.filter { it.isPrimaryKey }
        if (pkCols.isNotEmpty()) {
            return pkCols.associate { it.name to row[it.name] }
        }
        // 3. 无法唯一确定
        return null
    }

    fun updateRow(originalRow: RowData, updates: Map<String, Any?>) {
        val ns = namespace
        val db = dbName
        val table = tableName
        if (ns.isEmpty() || db.isEmpty() || table.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val where = resolveIdentity(originalRow)
                if (where == null) {
                    _uiState.value = _uiState.value.copy(
                        error = getApplication<Application>().getString(R.string.sql_no_rowid_edit_rejected)
                    )
                    return@launch
                }
                repository.updateRow(ns, db, table, where, updates)
                doLoadPage(_uiState.value.page)
                _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(R.string.updated))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.err_update_failed_with_reason, e.message ?: getApplication<Application>().getString(R.string.error_unknown)))
            }
        }
    }

    fun insertRow(values: Map<String, Any?>) {
        val ns = namespace
        val db = dbName
        val table = tableName
        if (ns.isEmpty() || db.isEmpty() || table.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.insertRow(ns, db, table, values)
                val newTotal = repository.countRows(ns, db, table)
                val pageSize = _uiState.value.pageSize
                val totalPages = if (newTotal == 0L) 0 else ((newTotal + pageSize - 1) / pageSize).toInt()
                _uiState.value = _uiState.value.copy(totalCount = newTotal, totalPages = totalPages)
                val targetPage = _uiState.value.page.coerceAtMost((totalPages - 1).coerceAtLeast(0))
                doLoadPage(targetPage)
                _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(R.string.added))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.kv_add_failed, e.message))
            }
        }
    }

    fun deleteRow(row: RowData) {
        val ns = namespace
        val db = dbName
        val table = tableName
        if (ns.isEmpty() || db.isEmpty() || table.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val where = resolveIdentity(row)
                if (where == null) {
                    _uiState.value = _uiState.value.copy(
                        error = getApplication<Application>().getString(R.string.sql_no_rowid_delete_rejected)
                    )
                    return@launch
                }
                repository.deleteRow(ns, db, table, where)
                val currentPage = _uiState.value.page
                val newTotal = repository.countRows(ns, db, table)
                val pageSize = _uiState.value.pageSize
                val totalPages = if (newTotal == 0L) 0 else ((newTotal + pageSize - 1) / pageSize).toInt()
                _uiState.value = _uiState.value.copy(totalCount = newTotal, totalPages = totalPages)
                val targetPage = currentPage.coerceAtMost((totalPages - 1).coerceAtLeast(0))
                doLoadPage(targetPage)
                _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(R.string.deleted))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.kv_delete_failed, e.message))
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
