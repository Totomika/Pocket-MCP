package io.github.totomika.pocketmcp.ui.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.data.log.LogEntry
import io.github.totomika.pocketmcp.data.log.LogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class LogsViewModel(app: Application) : AndroidViewModel(app) {
    private val logManager = app.container.logManager

    private val _selectedNamespace = MutableStateFlow<String?>(null)
    val selectedNamespace: StateFlow<String?> = _selectedNamespace.asStateFlow()

    private val _selectedType = MutableStateFlow<LogType?>(null)
    val selectedType: StateFlow<LogType?> = _selectedType.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val logs: StateFlow<List<LogEntry>> = combine(
        _selectedNamespace, _selectedType, _searchQuery.debounce(300)
    ) { ns, type, query -> Triple(ns, type, query) }
        .flatMapLatest { (ns, type, query) ->
            when {
                query.isNotBlank() -> logManager.search(query)
                ns != null && type != null -> logManager.observeByNamespaceAndType(ns, type)
                ns != null -> logManager.observeByNamespace(ns)
                type != null -> logManager.observeByType(type)
                else -> logManager.observeAll()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { logManager.cleanupOldLogs() }
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setSelectedType(type: LogType?) {
        _selectedType.value = type
    }

    fun setSelectedNamespace(ns: String?) {
        _selectedNamespace.value = ns
    }

    fun clearLogs() {
        viewModelScope.launch {
            val type = _selectedType.value
            val query = _searchQuery.value
            when {
                type != null && query.isNotBlank() -> logManager.clearByTypeAndSearch(type, query)
                type != null -> logManager.clearByType(type)
                query.isNotBlank() -> logManager.clearBySearch(query)
                else -> logManager.clearAll()
            }
        }
    }
}
