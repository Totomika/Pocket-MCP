package io.github.totomika.pocketmcp.ui.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.app.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OverviewUiState(
    val kvCount: Int? = null,
    val kvError: Boolean = false,
    val sqlDbCount: Int? = null,
    val sqlTotalSize: Long? = null,
    val sqlError: Boolean = false,
)

class DataOverviewViewModel(app: Application) : AndroidViewModel(app) {
    private val kvBrowserRepository = app.container.kvBrowserRepository
    private val sqlBrowserRepository = app.container.sqlBrowserRepository

    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    fun load(namespace: String) {
        _uiState.value = OverviewUiState()
        viewModelScope.launch(Dispatchers.IO) {
            // KV count
            try {
                val count = kvBrowserRepository.count(namespace)
                _uiState.value = _uiState.value.copy(kvCount = count, kvError = false)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(kvCount = null, kvError = true)
            }

            // SQL summary
            try {
                val dbs = sqlBrowserRepository.listDbs(namespace)
                _uiState.value = _uiState.value.copy(
                    sqlDbCount = dbs.size,
                    sqlTotalSize = dbs.sumOf { it.totalSizeBytes },
                    sqlError = false,
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    sqlDbCount = null,
                    sqlTotalSize = null,
                    sqlError = true,
                )
            }
        }
    }
}
