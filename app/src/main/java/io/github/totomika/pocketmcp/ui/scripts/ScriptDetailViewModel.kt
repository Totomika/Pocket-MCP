package io.github.totomika.pocketmcp.ui.scripts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.totomika.pocketmcp.app.container
import io.github.totomika.pocketmcp.mcp.ServiceEntry
import io.github.totomika.pocketmcp.permission.PermissionDisplay
import io.github.totomika.pocketmcp.permission.PermissionEntry
import io.github.totomika.pocketmcp.permission.PermissionParser
import io.github.totomika.pocketmcp.permission.PermissionToken
import io.github.totomika.pocketmcp.R
import io.github.totomika.pocketmcp.runtime.RuntimeFactory
import io.github.totomika.pocketmcp.script.RuntimeConfig
import io.github.totomika.pocketmcp.script.ScriptEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolInfo(val name: String, val description: String)
data class ServiceRef(
    val service: ServiceEntry,
    val serviceRunning: Boolean,
    val scriptEnabled: Boolean,
) {
    /** 脚本正通过此服务对外提供工具 (服务运行 + 脚本启用) */
    val isRunning: Boolean get() = serviceRunning && scriptEnabled
}

class ScriptDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val scriptManager = app.container.scriptManager
    private val permissionManager = app.container.permissionManager
    private val serviceManager = app.container.serviceManager

    private val _script = MutableStateFlow<ScriptEntry?>(null)
    val script: StateFlow<ScriptEntry?> = _script.asStateFlow()

    private val _permissions = MutableStateFlow<List<PermissionEntry>>(emptyList())
    val permissions: StateFlow<List<PermissionEntry>> = _permissions.asStateFlow()

    private val _permissionDisplays = MutableStateFlow<List<PermissionDisplay>>(emptyList())
    val permissionDisplays: StateFlow<List<PermissionDisplay>> = _permissionDisplays.asStateFlow()

    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    private val _tools = MutableStateFlow<List<ToolInfo>>(emptyList())
    val tools: StateFlow<List<ToolInfo>> = _tools.asStateFlow()

    private val _services = MutableStateFlow<List<ServiceRef>>(emptyList())
    val services: StateFlow<List<ServiceRef>> = _services.asStateFlow()

    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    private val _editCode = MutableStateFlow("")
    val editCode: StateFlow<String> = _editCode.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _showRestartDialog = MutableStateFlow(false)
    val showRestartDialog: StateFlow<Boolean> = _showRestartDialog.asStateFlow()

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _runtimeConfig = MutableStateFlow<RuntimeConfig?>(null)
    val runtimeConfig: StateFlow<RuntimeConfig?> = _runtimeConfig.asStateFlow()

    /** 运行时内存使用快照 (usedBytes, limitBytes), null = 未运行 */
    private val _memoryUsage = MutableStateFlow<Pair<Long, Long>?>(null)
    val memoryUsage: StateFlow<Pair<Long, Long>?> = _memoryUsage.asStateFlow()

    fun load(namespace: String) {
        viewModelScope.launch {
            _script.value = scriptManager.getScript(namespace)
            _permissions.value = permissionManager.getDeclared(namespace)
            _code.value = scriptManager.readScriptCode(namespace)
            _permissionDisplays.value = PermissionParser.parse(
                scriptManager.readScriptCode(namespace) ?: ""
            ).map { PermissionDisplay.from(it) }

            val code = _code.value ?: ""
            _tools.value = extractTools(code)

            val allServices = serviceManager.getAllServices()
            _services.value = allServices.mapNotNull { service ->
                val scripts = serviceManager.getServiceScripts(service.id)
                val ref = scripts.find { it.namespace == namespace }
                if (ref != null) {
                    val svcInstance = serviceManager.getService(service.id)
                    ServiceRef(
                        service = service,
                        serviceRunning = svcInstance != null,
                        scriptEnabled = ref.enabled,
                    )
                } else null
            }

            // 加载 runtimeConfig
            val app = getApplication<android.app.Application>()
            _runtimeConfig.value = app.container.scriptManifestStore.readSync(namespace)?.runtimeConfig

            // 加载内存使用 (若 runtime 正在运行)
            refreshMemoryUsage(namespace)
        }
    }

    private fun refreshMemoryUsage(namespace: String) {
        val app = getApplication<android.app.Application>()
        val runtime = app.container.runtimeManager.getRuntime(namespace)
        if (runtime != null && !runtime.quickJs.isClosed) {
            try {
                val used = runtime.memoryUsage.memoryUsedSize
                val limit = runtime.quickJs.memoryLimit
                _memoryUsage.value = Pair(used, limit)
            } catch (e: Exception) {
                _memoryUsage.value = null
            }
        } else {
            _memoryUsage.value = null
        }
    }

    fun toggleEditMode() {
        val current = _code.value ?: return
        _editMode.value = !_editMode.value
        if (_editMode.value) {
            _editCode.value = current
        }
    }

    fun updateEditCode(code: String) {
        _editCode.value = code
    }

    fun saveCode(namespace: String) {
        viewModelScope.launch {
            val newCode = _editCode.value
            val app = getApplication<android.app.Application>()
            app.container.scriptRepository.storeScriptCode(namespace, newCode)
            serviceManager.registerScriptCode(namespace, newCode)
            _code.value = newCode
            _tools.value = extractTools(newCode)
            _editMode.value = false
            _message.value = getApplication<Application>().getString(R.string.code_saved)

            // 已有服务在运行该脚本时, 其 runtime 仍持有旧代码 (RuntimeManager 引用计数不会重新 evaluate)。
            // 弹窗提示用户重启, 让 refCount 归零后重新 evaluate 新代码。
            val hasRunning = _services.value.any { it.isRunning }
            if (hasRunning) {
                _showRestartDialog.value = true
            }
        }
    }

    /**
     * 重启所有正在运行该脚本的服务, 使编辑后的新代码生效。
     */
    fun restartRunningServices() {
        viewModelScope.launch {
            val ns = _script.value?.namespace ?: return@launch
            val total = _services.value.count { it.isRunning }
            _isRestarting.value = true
            try {
                // 重启链路 = stop + start (acquire → evaluate + 中毒重建探测), 不能跑 Main
                val started = withContext(Dispatchers.Default) {
                    serviceManager.restartServicesForScript(ns)
                }
                _showRestartDialog.value = false
                _message.value = getApplication<Application>().getString(R.string.services_restarted, started, total)
            } catch (e: Exception) {
                _message.value = getApplication<Application>().getString(R.string.restart_failed, e.message ?: getApplication<Application>().getString(R.string.error_unknown))
            } finally {
                _isRestarting.value = false
                // 刷新服务列表, 重启失败的会显示为未运行
                load(ns)
            }
        }
    }

    fun dismissRestartDialog() {
        _showRestartDialog.value = false
    }

    fun checkUpdates(namespace: String) {
        viewModelScope.launch {
            _message.value = getApplication<Application>().getString(R.string.checking_updates)
            try {
                val updates = scriptManager.checkUrlUpdates()
                val update = updates.find { it.namespace == namespace }
                _message.value = if (update != null) {
                    getApplication<Application>().getString(R.string.update_found, update.newVersion, update.currentVersion)
                } else {
                    getApplication<Application>().getString(R.string.already_latest)
                }
            } catch (e: Exception) {
                _message.value = getApplication<Application>().getString(R.string.check_update_failed, e.message ?: getApplication<Application>().getString(R.string.error_unknown))
            }
        }
    }

    fun uninstall(namespace: String, deleteData: Boolean, purgeLogs: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            // uninstallScript 链路含 restartServicesForScript (stop+start → evaluate),
            // 不能在 Main 线程跑
            val restarted = withContext(Dispatchers.Default) {
                scriptManager.uninstallScript(
                    namespace,
                    purgeData = deleteData,
                    purgeLogs = purgeLogs
                )
            }
            _message.value =
                if (restarted > 0) getApplication<Application>().getString(R.string.uninstalled_with_restart, restarted) else getApplication<Application>().getString(R.string.uninstalled)
            onDone()
        }
    }

    fun grantPermission(token: PermissionToken, spec: String?) {
        viewModelScope.launch {
            val ns = _script.value?.namespace ?: return@launch
            permissionManager.grant(ns, token, spec)
            refreshPermissions()
            _message.value = getApplication<Application>().getString(R.string.permission_granted, token.token)
        }
    }

    fun revokePermission(token: PermissionToken, spec: String?) {
        viewModelScope.launch {
            val ns = _script.value?.namespace ?: return@launch
            permissionManager.revoke(ns, token, spec)
            refreshPermissions()
            _message.value = getApplication<Application>().getString(R.string.permission_revoked, token.token)
        }
    }

    fun grantAllPermissions() {
        viewModelScope.launch {
            val ns = _script.value?.namespace ?: return@launch
            permissionManager.grantAll(ns)
            refreshPermissions()
            _message.value = getApplication<Application>().getString(R.string.all_permissions_granted)
        }
    }

    fun revokeAllPermissions() {
        viewModelScope.launch {
            val ns = _script.value?.namespace ?: return@launch
            permissionManager.revokeAll(ns)
            refreshPermissions()
            _message.value = getApplication<Application>().getString(R.string.all_permissions_revoked)
        }
    }

    private suspend fun refreshPermissions() {
        val ns = _script.value?.namespace ?: return
        _permissions.value = permissionManager.getDeclared(ns)
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * 切换脚本在指定服务中的启用状态。
     */
    fun toggleScriptEnabled(serviceId: String, namespace: String, enabled: Boolean) {
        viewModelScope.launch {
            // 服务运行中 toggle 会 rebuildTools → acquire → evaluate, 不能在 Main 线程跑
            withContext(Dispatchers.Default) {
                serviceManager.toggleScriptEnabled(serviceId, namespace, enabled)
            }
            // 刷新服务列表以反映新的运行状态
            val allServices = serviceManager.getAllServices()
            _services.value = allServices.mapNotNull { service ->
                val scripts = serviceManager.getServiceScripts(service.id)
                val ref = scripts.find { it.namespace == namespace }
                if (ref != null) {
                    val svcInstance = serviceManager.getService(service.id)
                    ServiceRef(
                        service = service,
                        serviceRunning = svcInstance != null,
                        scriptEnabled = ref.enabled,
                    )
                } else null
            }
            // 切换启用/禁用会 acquire/release runtime, 同步刷新内存使用
            refreshMemoryUsage(namespace)
        }
    }

    /**
     * 保存运行时高级配置到 manifest, 并实时应用到运行中的 runtime。
     */
    fun saveRuntimeConfig(config: RuntimeConfig) {
        viewModelScope.launch {
            val ns = _script.value?.namespace ?: return@launch
            val app = getApplication<android.app.Application>()
            app.container.scriptManifestStore.update(ns) { manifest ->
                manifest.copy(runtimeConfig = config)
            }
            _runtimeConfig.value = config

            // 若 runtime 正在运行, 热更新
            app.container.runtimeManager.updateRuntimeConfig(ns, config)
            refreshMemoryUsage(ns)

            _message.value = getApplication<Application>().getString(R.string.advanced_settings_saved)
        }
    }

    private fun extractTools(code: String): List<ToolInfo> {
        val regex = Regex("""mcp\.tool\(\s*["']([^"']+)["']\s*,\s*["']([^"']*)["']""")
        return regex.findAll(code).map { match ->
            ToolInfo(
                name = match.groupValues[1],
                description = match.groupValues[2],
            )
        }.toList()
    }
}
