package io.github.totomika.pocketmcp.ui.data.kv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.data.sql.SortDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KvUiState(
    val items: List<Pair<String, String>> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 0,
    val totalPages: Int = 0,
    val sortDir: SortDir = SortDir.ASC,
    val searchQuery: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 等待用户确认是否覆盖已存在 key 的待写入条目 (key, value).
 */
data class KvPendingOverwrite(val key: String, val value: String)

class KvManageViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app
    private val repo = app.container.kvBrowserRepository

    private val _uiState = MutableStateFlow(KvUiState())
    val uiState: StateFlow<KvUiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _pendingOverwrite = MutableStateFlow<KvPendingOverwrite?>(null)
    val pendingOverwrite: StateFlow<KvPendingOverwrite?> = _pendingOverwrite.asStateFlow()

    private var namespace: String = ""

    /** 串行化所有查询加载, 取消旧 doLoad, 避免竞态. */
    private var loadJob: Job? = null

    fun load(namespace: String) {
        this.namespace = namespace
        triggerLoad()
    }

    fun setSearchQuery(q: String) {
        _uiState.update { it.copy(searchQuery = q, page = 0) }
        scheduleDebouncedLoad()
    }

    fun toggleSortDir() {
        _uiState.update {
            it.copy(
                sortDir = if (it.sortDir == SortDir.ASC) SortDir.DESC else SortDir.ASC,
                page = 0,
            )
        }
        triggerLoad()
    }

    fun setPage(page: Int) {
        val current = _uiState.value
        val clamped = page.coerceIn(0, (current.totalPages - 1).coerceAtLeast(0))
        if (clamped == current.page) return
        _uiState.update { it.copy(page = clamped) }
        triggerLoad()
    }

    /**
     * 添加键值对. 若 key 已存在, 不直接覆盖, 而是触发 [pendingOverwrite] 让 UI 弹出确认.
     */
    fun addEntry(key: String, value: String) {
        if (namespace.isEmpty()) return
        val k = key.trim()
        if (k.isEmpty()) {
            _message.value = application.getString(R.string.kv_key_empty_error)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (repo.get(namespace, k) != null) {
                    _pendingOverwrite.value = KvPendingOverwrite(k, value)
                    return@launch
                }
                repo.set(namespace, k, value)
                _message.value = application.getString(R.string.added)
                triggerLoad()
            } catch (e: Exception) {
                _message.value = application.getString(R.string.kv_add_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    /** 用户确认覆盖已存在的 key. */
    fun confirmOverwrite() {
        val pending = _pendingOverwrite.value ?: return
        _pendingOverwrite.value = null
        if (namespace.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.set(namespace, pending.key, pending.value)
                _message.value = application.getString(R.string.kv_saved)
                triggerLoad()
            } catch (e: Exception) {
                _message.value = application.getString(R.string.kv_save_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    /** 用户取消覆盖. */
    fun cancelOverwrite() {
        _pendingOverwrite.value = null
    }

    fun updateEntry(key: String, value: String) {
        if (namespace.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.set(namespace, key, value)
                _message.value = application.getString(R.string.updated)
                triggerLoad()
            } catch (e: Exception) {
                _message.value = application.getString(R.string.kv_update_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    fun deleteEntry(key: String) {
        if (namespace.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.delete(namespace, key)
                _message.value = application.getString(R.string.deleted)
                triggerLoad()
            } catch (e: Exception) {
                _message.value = application.getString(R.string.kv_delete_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    fun clearAll() {
        if (namespace.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.clear(namespace)
                _message.value = application.getString(R.string.kv_cleared)
                _uiState.update {
                    it.copy(page = 0, searchQuery = "", sortDir = SortDir.ASC)
                }
                triggerLoad()
            } catch (e: Exception) {
                _message.value = application.getString(R.string.kv_clear_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun triggerLoad() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { doLoad() }
    }

    private fun scheduleDebouncedLoad() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            doLoad()
        }
    }

    /**
     * 重新加载当前页数据, 并根据最新 totalCount 自动修正 page 越界.
     * 失败时保留旧 items/totalCount, 仅写入 error.
     */
    private suspend fun doLoad() {
        val ns = namespace
        if (ns.isEmpty()) return
        _uiState.update { it.copy(loading = true, error = null) }
        try {
            val total = withContext(Dispatchers.IO) { repo.count(ns) }
            val totalPages = if (total == 0) 0 else (total - 1) / PAGE_SIZE + 1
            val page = _uiState.value.page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            val offset = page * PAGE_SIZE
            val sortDir = _uiState.value.sortDir
            val query = _uiState.value.searchQuery
            val items = withContext(Dispatchers.IO) {
                repo.page(ns, offset, PAGE_SIZE, sortDir, query)
            }
            _uiState.update {
                it.copy(
                    items = items,
                    totalCount = total,
                    totalPages = totalPages,
                    page = page,
                    loading = false,
                    error = null,
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(loading = false, error = e.message ?: application.getString(R.string.data_load_failed)) }
        }
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
