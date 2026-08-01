package io.github.totomika.pocketmcp.ui.data.sql

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.data.sql.DbFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SqlDbListUiState(
    val dbs: List<DbFileInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class SqlDbListViewModel(app: Application) : AndroidViewModel(app) {
    private val sqlBrowserRepository = app.container.sqlBrowserRepository

    private val _uiState = MutableStateFlow(SqlDbListUiState())
    val uiState: StateFlow<SqlDbListUiState> = _uiState.asStateFlow()

    fun load(namespace: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val dbs = sqlBrowserRepository.listDbs(namespace)
                _uiState.value = _uiState.value.copy(
                    dbs = dbs,
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

    fun deleteDb(namespace: String, dbName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sqlBrowserRepository.deleteDb(namespace, dbName)
                load(namespace)
                _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(R.string.sql_db_deleted_name, dbName))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = getApplication<Application>().getString(R.string.kv_delete_failed, e.message),
                )
            }
        }
    }

    fun renameDb(namespace: String, oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (newName.isBlank()) {
                    _uiState.value = _uiState.value.copy(error = getApplication<Application>().getString(R.string.name_empty_error))
                    return@launch
                }
                sqlBrowserRepository.renameDb(namespace, oldName, newName)
                load(namespace)
                _uiState.value = _uiState.value.copy(message = getApplication<Application>().getString(R.string.sql_renamed, oldName, newName))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = getApplication<Application>().getString(R.string.sql_rename_db_failed, e.message),
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
