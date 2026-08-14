package io.github.totomika.pocketmcp.ui.services

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.mcp.ServiceEntry
import io.github.totomika.pocketmcp.script.ScriptEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出配置的目标客户端格式。
 *
 * [displayNameRes] / [hintRes] 为字符串资源 ID, 由 UI 层通过 `stringResource(...)` 解析,
 * 以避免在 enum 构造期访问 Application 上下文。
 */
enum class ExportFormat(val displayNameRes: Int, val hintRes: Int) {
    GENERIC(R.string.export_format_generic, R.string.export_hint_generic),
    CLINE(R.string.export_format_cline, R.string.export_hint_cline),
    VSCODE(R.string.export_format_vscode, R.string.export_hint_vscode),
}

/**
 * 详情页脚本项的汇总信息。
 */
data class ScriptDetail(
    val namespace: String,
    val enabled: Boolean,
    val name: String,
    val version: String,
    val tools: List<String>,
)

class ServiceDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app
    private val serviceManager = app.container.serviceManager
    private val scriptRepository = app.container.scriptRepository
    private val scriptManager = app.container.scriptManager

    private val _service = MutableStateFlow<ServiceEntry?>(null)
    val service: StateFlow<ServiceEntry?> = _service.asStateFlow()

    private val _scriptDetails = MutableStateFlow<List<ScriptDetail>>(emptyList())
    val scriptDetails: StateFlow<List<ScriptDetail>> = _scriptDetails.asStateFlow()

    private val _availableScripts = MutableStateFlow<List<ScriptEntry>>(emptyList())
    val availableScripts: StateFlow<List<ScriptEntry>> = _availableScripts.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load(serviceId: String) {
        viewModelScope.launch {
            _service.value = serviceManager.getServiceById(serviceId)
            _isRunning.value = serviceManager.getService(serviceId) != null
            refreshScriptDetails(serviceId)
        }
    }

    private suspend fun refreshScriptDetails(serviceId: String) {
        val scripts = serviceManager.getServiceScripts(serviceId)
        val service = serviceManager.getService(serviceId)
        val registeredTools = service?.registeredTools ?: emptySet()
        val isRunning = service != null

        _scriptDetails.value = scripts.map { ps ->
            val metadata = scriptManager.getScript(ps.namespace)
            val tools = if (ps.enabled && isRunning) {
                registeredTools.filter { it.startsWith("${ps.namespace}_") }.toList()
            } else emptyList()
            ScriptDetail(
                namespace = ps.namespace,
                enabled = ps.enabled,
                name = metadata?.name ?: ps.namespace,
                version = metadata?.version ?: "unknown",
                tools = tools,
            )
        }
    }

    fun toggleScript(serviceId: String, namespace: String, enabled: Boolean) {
        viewModelScope.launch {
            // 服务运行中 toggle 会 rebuildTools → acquire → evaluate, 不能在 Main 线程跑
            withContext(Dispatchers.Default) {
                serviceManager.toggleScriptEnabled(serviceId, namespace, enabled)
            }
            refreshScriptDetails(serviceId)
        }
    }

    fun start(serviceId: String) {
        viewModelScope.launch {
            try {
                // 启动链路含 acquire → evaluate (可能死循环) + 中毒重建探测, 不能跑 Main
                withContext(Dispatchers.Default) {
                    serviceManager.startService(serviceId)
                }
                _isRunning.value = true
                refreshScriptDetails(serviceId)
            } catch (e: Exception) {
                _message.value = application.getString(R.string.start_service_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    fun stop(serviceId: String) {
        viewModelScope.launch {
            try {
                // 停止链路含 release → destroy (中毒时探测最多 2s), 不能跑 Main
                withContext(Dispatchers.Default) {
                    serviceManager.stopService(serviceId)
                }
                _isRunning.value = false
                refreshScriptDetails(serviceId)
            } catch (e: Exception) {
                _message.value = application.getString(R.string.stop_service_failed, e.message ?: application.getString(R.string.error_unknown))
            }
        }
    }

    fun deleteService(serviceId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            // 删除链路含 stopServiceInternal → release → destroy, 同上不能跑 Main
            withContext(Dispatchers.Default) {
                serviceManager.deleteService(serviceId)
            }
            onDone()
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun updateService(serviceId: String, name: String, port: Int) {
        viewModelScope.launch {
            val current = _service.value ?: return@launch
            if (current.port != port && _isRunning.value) {
                _message.value = application.getString(R.string.edit_port_warning)
                return@launch
            }
            try {
                serviceManager.updateService(current.copy(name = name, port = port))
                _service.value = current.copy(name = name, port = port)
                _message.value = application.getString(R.string.config_updated)
            } catch (e: Exception) {
                _message.value = when (e) {
                    is io.github.totomika.pocketmcp.mcp.ServiceNameInUseException ->
                        application.getString(R.string.update_failed_name_taken, e.name)

                    is io.github.totomika.pocketmcp.mcp.PortInUseException ->
                        application.getString(R.string.update_failed_port_taken)

                    else -> application.getString(R.string.err_update_failed_with_reason, e.message ?: application.getString(R.string.error_unknown))
                }
            }
        }
    }

    // region 添加/移除脚本

    /** 加载尚未添加到此服务的脚本列表。 */
    fun loadAvailableScripts(serviceId: String) {
        viewModelScope.launch {
            val allScripts = scriptManager.getAllScripts()
            val currentNamespaces = _scriptDetails.value.map { it.namespace }.toSet()
            _availableScripts.value = allScripts.filter { it.namespace !in currentNamespaces }
        }
    }

    /** 批量添加脚本到服务。 */
    fun addScripts(serviceId: String, namespaces: List<String>) {
        viewModelScope.launch {
            // 服务运行中 addScriptToService 会 rebuildTools → acquire → evaluate, 不能在 Main 线程跑
            withContext(Dispatchers.Default) {
                for (namespace in namespaces) {
                    val code = scriptRepository.readScriptCode(namespace) ?: continue
                    serviceManager.addScriptToService(serviceId, namespace, code, enabled = true)
                }
            }
            refreshScriptDetails(serviceId)
        }
    }

    /** 从服务移除脚本。 */
    fun removeScript(serviceId: String, namespace: String) {
        viewModelScope.launch {
            // 服务运行中 removeScriptFromService 会 rebuildTools → acquire → evaluate, 不能在 Main 线程跑
            withContext(Dispatchers.Default) {
                serviceManager.removeScriptFromService(serviceId, namespace)
            }
            refreshScriptDetails(serviceId)
        }
    }

    // endregion

    /**
     * 生成导出配置 JSON。格式取决于目标客户端。
     */
    fun generateExportConfig(format: ExportFormat, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val p = _service.value ?: return@launch
            val url = "http://127.0.0.1:${p.port}/mcp"
            val json = when (format) {
                ExportFormat.GENERIC -> """{
  "mcpServers": {
    "${p.name}": {
      "url": "$url"
    }
  }
}"""

                ExportFormat.CLINE -> """{
  "mcpServers": {
    "${p.name}": {
      "type": "streamableHttp",
      "url": "$url",
      "disabled": false,
      "autoApprove": []
    }
  }
}"""

                ExportFormat.VSCODE -> """{
  "servers": {
    "${p.name}": {
      "type": "http",
      "url": "$url"
    }
  }
}"""
            }
            onResult(json)
        }
    }
}
