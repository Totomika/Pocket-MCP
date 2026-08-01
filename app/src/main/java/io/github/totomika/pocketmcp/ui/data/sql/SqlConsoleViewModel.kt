package io.github.totomika.pocketmcp.ui.data.sql

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.data.sql.RowData
import io.github.totomika.pocketmcp.data.sql.SqlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConsoleResultState {
    data object Idle : ConsoleResultState
    data object Loading : ConsoleResultState
    data class Select(val columns: List<String>, val rows: List<RowData>) : ConsoleResultState
    data class Update(val affectedRows: Int) : ConsoleResultState
    data class Error(val message: String) : ConsoleResultState
}

data class SqlConsoleUiState(
    val sql: String = "",
    val result: ConsoleResultState = ConsoleResultState.Idle,
    val history: List<String> = emptyList(),
    val isExecuting: Boolean = false,
)

class SqlConsoleViewModel(app: Application) : AndroidViewModel(app) {
    private val sqlBrowserRepository = app.container.sqlBrowserRepository

    private val _uiState = MutableStateFlow(SqlConsoleUiState())
    val uiState: StateFlow<SqlConsoleUiState> = _uiState.asStateFlow()

    fun setSql(text: String) {
        _uiState.value = _uiState.value.copy(sql = text)
    }

    fun clearSql() {
        _uiState.value = _uiState.value.copy(sql = "")
    }

    fun applyHistory(sql: String) {
        _uiState.value = _uiState.value.copy(sql = sql)
    }

    fun run(namespace: String, dbName: String) {
        val sql = _uiState.value.sql.trim()
        if (sql.isBlank()) return

        _uiState.value = _uiState.value.copy(result = ConsoleResultState.Loading, isExecuting = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = sqlBrowserRepository.exec(namespace, dbName, sql)
                val state = when (result) {
                    is SqlResult.Select -> ConsoleResultState.Select(
                        columns = result.columns,
                        rows = result.rows,
                    )
                    is SqlResult.Update -> ConsoleResultState.Update(affectedRows = result.affectedRows)
                    is SqlResult.Error -> ConsoleResultState.Error(message = result.message)
                }

                val newHistory = if (result !is SqlResult.Error) {
                    buildList {
                        add(sql)
                        addAll(_uiState.value.history.filter { it != sql }.take(9))
                    }
                } else {
                    _uiState.value.history
                }

                _uiState.value = _uiState.value.copy(
                    result = state,
                    history = newHistory,
                    isExecuting = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    result = ConsoleResultState.Error(
                        message = e.message ?: e.javaClass.simpleName
                    ),
                    isExecuting = false,
                )
            }
        }
    }
}
