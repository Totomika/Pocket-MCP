package io.github.totomika.pocketmcp.ui.data.sql

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.data.sql.SqlBrowserRepository
import io.github.totomika.pocketmcp.data.sql.TableSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SqlDbDetailUiState(
    val tables: List<TableSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class SqlDbDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val sqlBrowserRepository: SqlBrowserRepository = app.container.sqlBrowserRepository

    private val _uiState = MutableStateFlow(SqlDbDetailUiState())
    val uiState: StateFlow<SqlDbDetailUiState> = _uiState.asStateFlow()

    fun load(namespace: String, dbName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val tables = sqlBrowserRepository.listTables(namespace, dbName)
                _uiState.value = _uiState.value.copy(tables = tables, loading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = e.message ?: getApplication<Application>().getString(R.string.data_load_failed)
                )
            }
        }
    }

    fun deleteTable(namespace: String, dbName: String, table: String) {
        viewModelScope.launch {
            try {
                sqlBrowserRepository.deleteTable(namespace, dbName, table)
                _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(R.string.sql_table_deleted))
                load(namespace, dbName)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: getApplication<Application>().getString(R.string.sql_delete_table_failed)
                )
            }
        }
    }

    fun renameTable(namespace: String, dbName: String, oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (newName.isBlank()) {
                    _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.name_empty_error))
                    return@launch
                }
                sqlBrowserRepository.renameTable(namespace, dbName, oldName, newName)
                _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(R.string.sql_renamed, oldName, newName))
                load(namespace, dbName)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: getApplication<Application>().getString(R.string.sql_rename_table_failed)
                )
            }
        }
    }

    fun deleteDb(namespace: String, dbName: String) {
        viewModelScope.launch {
            try {
                sqlBrowserRepository.deleteDb(namespace, dbName)
                _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(R.string.sql_db_deleted))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: getApplication<Application>().getString(R.string.sql_delete_db_failed)
                )
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
