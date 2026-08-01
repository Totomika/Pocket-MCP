package io.github.totomika.pocketmcp.ui.scripts

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.script.ScriptManager
import io.github.totomika.pocketmcp.script.ScriptSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddScriptViewModel(app: Application) : AndroidViewModel(app) {
    private val scriptManager = app.container.scriptManager

    private val _result = MutableStateFlow<ScriptManager.ImportResult?>(null)
    val result: StateFlow<ScriptManager.ImportResult?> = _result.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _pendingUpdate = MutableStateFlow<PendingUpdate?>(null)
    val pendingUpdate: StateFlow<PendingUpdate?> = _pendingUpdate.asStateFlow()

    fun importFromPaste(code: String) {
        doImport(code, ScriptSourceType.PASTE, null)
    }

    fun importFromUrl(url: String) {
        _loading.value = true
        viewModelScope.launch {
            _result.value = scriptManager.importFromUrl(url)
            _loading.value = false
        }
    }

    fun importFromFile(uri: Uri) {
        _loading.value = true
        viewModelScope.launch {
            try {
                val code = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException(
                        getApplication<Application>().getString(R.string.err_cannot_read_file)
                    )
                _result.value = scriptManager.importScript(code, ScriptSourceType.FILE)
            } catch (e: Exception) {
                _result.value = ScriptManager.ImportResult.Error(
                    e.message ?: getApplication<Application>().getString(R.string.err_file_read_failed)
                )
            }
            _loading.value = false
        }
    }

    private fun doImport(code: String, sourceType: ScriptSourceType, sourceUrl: String?) {
        _loading.value = true
        viewModelScope.launch {
            val r = scriptManager.importScript(code, sourceType, sourceUrl)
            if (r is ScriptManager.ImportResult.UpdateAvailable) {
                _pendingUpdate.value = PendingUpdate(
                    namespace = r.existing.namespace,
                    newCode = code,
                    sourceUrl = sourceUrl,
                    existingVersion = r.existing.version,
                    newVersion = r.newVersion,
                    newPermissions = r.newPermissions,
                )
            } else {
                _result.value = r
            }
            _loading.value = false
        }
    }

    fun confirmUpdate() {
        val pending = _pendingUpdate.value ?: return
        viewModelScope.launch {
            _loading.value = true
            try {
                scriptManager.confirmUpdate(pending.namespace, pending.newCode, pending.sourceUrl)
                _result.value = ScriptManager.ImportResult.Imported(
                    scriptManager.getScript(pending.namespace)!!
                )
            } catch (e: Exception) {
                _result.value = ScriptManager.ImportResult.Error(
                    e.message ?: getApplication<Application>().getString(R.string.err_update_failed)
                )
            }
            _loading.value = false
            _pendingUpdate.value = null
        }
    }

    fun cancelUpdate() {
        _pendingUpdate.value = null
    }

    fun clearResult() {
        _result.value = null
    }
}

data class PendingUpdate(
    val namespace: String,
    val newCode: String,
    val sourceUrl: String?,
    val existingVersion: String,
    val newVersion: String,
    val newPermissions: List<io.github.totomika.pocketmcp.permission.PermissionDeclaration>,
)
