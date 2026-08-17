package io.github.totomika.pocketmcp.ui.services

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.mcp.ServiceEntry
import io.github.totomika.pocketmcp.script.ScriptEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 服务列表项的汇总信息。
 */
data class ServiceSummary(
    val service: ServiceEntry,
    val scriptCount: Int,
    val toolCount: Int,
    val isRunning: Boolean,
    val subtitle: String,
)

class ServicesViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app
    private val serviceManager = app.container.serviceManager
    private val scriptManager = app.container.scriptManager

    private val _services = MutableStateFlow<List<ServiceEntry>>(emptyList())

    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
    val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0)

    val summaries: StateFlow<List<ServiceSummary>> =
        combine(_services, _runningIds, _refreshTrigger) { services, running, _ ->
            buildSummaries(services, running)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _allScripts = MutableStateFlow<List<ScriptEntry>>(emptyList())
    val allScripts: StateFlow<List<ScriptEntry>> = _allScripts.asStateFlow()

    private val _previewPort = MutableStateFlow<Int?>(null)
    val previewPort: StateFlow<Int?> = _previewPort.asStateFlow()

    private val _isPortLoading = MutableStateFlow(false)
    val isPortLoading: StateFlow<Boolean> = _isPortLoading.asStateFlow()

    val portRangeHint: String = "${serviceManager.portRange.first}-${serviceManager.portRange.last}"

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _services.value = serviceManager.getAllServices()
            syncRunningIds()
        }
    }

    private suspend fun syncRunningIds() {
        val all = serviceManager.getAllServices()
        _runningIds.value = all
            .filter { serviceManager.getService(it.id) != null }
            .map { it.id }
            .toSet()
    }

    fun loadAllScripts() {
        viewModelScope.launch {
            _isPortLoading.value = true
            _allScripts.value = scriptManager.getAllScripts()
            _previewPort.value = serviceManager.findNextPort()
            _isPortLoading.value = false
        }
    }

    private suspend fun buildSummaries(
        services: List<ServiceEntry>,
        running: Set<String>,
    ): List<ServiceSummary> {
        return services.map { service ->
            val scripts = serviceManager.getServiceScripts(service.id)
            val isRunning = service.id in running
            val toolCount = if (isRunning) {
                serviceManager.getService(service.id)?.registeredTools?.size ?: 0
            } else 0
            val subtitle = buildSubtitle(service, scripts.size, toolCount, isRunning)
            ServiceSummary(service, scripts.size, toolCount, isRunning, subtitle)
        }
    }

    private suspend fun buildSubtitle(
        service: ServiceEntry,
        scriptCount: Int,
        toolCount: Int,
        isRunning: Boolean,
    ): String {
        val toolPart = if (isRunning && toolCount > 0)
            getApplication<Application>().getString(R.string.service_tool_count_part, toolCount)
        else ""
        return if (service.autoCreated) {
            val scripts = serviceManager.getServiceScripts(service.id)
            val scriptName = scripts.firstOrNull()?.let {
                scriptManager.getScript(it.namespace)?.name
            } ?: service.name
            scriptName + toolPart
        } else {
            getApplication<Application>().getString(R.string.service_scripts_summary, scriptCount, toolPart)
        }
    }

    fun startService(id: String) {
        viewModelScope.launch {
            try {
                // manager 内部已切换到 IO dispatcher, Main 调用安全
                serviceManager.startService(id)
                _runningIds.value = _runningIds.value + id
            } catch (e: Exception) {
                _message.value = application.getString(R.string.start_service_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    fun stopService(id: String) {
        viewModelScope.launch {
            try {
                // manager 内部已切换到 IO dispatcher, Main 调用安全
                serviceManager.stopService(id)
                _runningIds.value = _runningIds.value - id
            } catch (e: Exception) {
                _message.value = application.getString(R.string.stop_service_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun createService(
        name: String,
        port: Int?,
        selectedNamespaces: List<String> = emptyList(),
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val actualPort = port ?: _previewPort.value
                ?: throw IllegalStateException(application.getString(R.string.no_port_available, portRangeHint))
                // manager 内部已切换到 IO dispatcher, Main 调用安全
                val service = serviceManager.createService(name, actualPort)
                for (namespace in selectedNamespaces) {
                    val code = scriptManager.readScriptCode(namespace) ?: continue
                    serviceManager.addScriptToService(service.id, namespace, code, enabled = true)
                }
                reload()
                _message.value = application.getString(R.string.service_created, service.name)
                onResult(service.id)
            } catch (e: Exception) {
                Log.w("ServicesViewModel", "createService failed: name=$name", e)
                val friendly = when (e) {
                    is io.github.totomika.pocketmcp.mcp.ServiceNameInUseException ->
                        application.getString(R.string.create_failed_name_taken, e.name)

                    is io.github.totomika.pocketmcp.mcp.PortInUseException ->
                        application.getString(R.string.create_failed_port_taken)

                    else -> application.getString(R.string.create_failed, e.message ?: application.getString(R.string.error_unknown))
                }
                _message.value = friendly
                onResult(null)
            }
        }
    }
}
