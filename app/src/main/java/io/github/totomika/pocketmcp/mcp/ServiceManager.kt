package io.github.totomika.pocketmcp.mcp

import io.github.totomika.pocketmcp.runtime.RuntimeManager
import io.github.totomika.pocketmcp.script.RuntimeConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 服务 (MCP Server) 管理器。
 *
 * 职责:
 * 1. Service CRUD (创建、查询、更新、删除) — 走 [ServiceManifestStore]
 * 2. 脚本添加/移除到服务 (工具白名单)
 * 3. 启动/停止服务 (管理 [McpServiceInstance] 生命周期)
 * 4. runtime 引用计数管理 (通过 [RuntimeManager])
 * 5. tools/list_changed 通知 (脚本增删/勾选变化时)
 *
 * 重构后: 持久化全走 `files/services/<svcId>/manifest.json`, 不再依赖 Room。
 * "Profile" 系列已全部改名 "Service", 命名与 UI "服务" 统一, 消除歧义。
 *
 * @param manifestStore 服务清单文件存储
 * @param portManager 端口分配
 * @param runtimeManager runtime 引用计数管理
 * @param serverFactory MCP Server 工厂
 * @param scriptCodes namespace → 脚本源码 (由脚本分发填充, 测试时手动注入)
 */
class ServiceManager(
    private val manifestStore: ServiceManifestStore,
    private val portManager: PortManager,
    private val runtimeManager: RuntimeManager,
    private val serverFactory: McpServerFactory,
    private val codeLoader: (String) -> String? = { null },
    private val runtimeConfigLoader: (String) -> RuntimeConfig? = { null },
    private val logManager: io.github.totomika.pocketmcp.data.log.LogManager? = null,
    private var scriptCodes: MutableMap<String, String> = mutableMapOf(),
) {
    /** 活跃的服务实例: serviceId → McpServiceInstance */
    private val activeServices = mutableMapOf<String, McpServiceInstance>()
    private val mutex = Mutex()

    /** 所有服务的内存快照 (供 UI 响应式消费, 没有 Room Flow 订阅源, 走显式 reload)。 */
    private val _services = MutableStateFlow<List<ServiceEntry>>(emptyList())
    val services: StateFlow<List<ServiceEntry>> = _services.asStateFlow()

    // region 脚本代码缓存

    /**
     * 确保脚本代码已加载到 scriptCodes。
     *
     * scriptCodes 是内存 Map, app 重启后清空。
     * 需要从磁盘重新加载。
     */
    private fun ensureCodeLoaded(namespace: String) {
        if (!scriptCodes.containsKey(namespace)) {
            codeLoader(namespace)?.let { code ->
                scriptCodes[namespace] = code
            }
        }
    }

    /** 注册脚本源码 (脚本导入时调用)。 */
    fun registerScriptCode(namespace: String, code: String) {
        scriptCodes[namespace] = code
    }

    /** 清除脚本源码缓存 (脚本卸载时调用)。 */
    fun removeScriptCode(namespace: String) {
        scriptCodes.remove(namespace)
    }

    /** 重新加载全部服务快照 (每次 CRUD 后调用)。 */
    suspend fun reload() {
        val all = manifestStore.listAll()
        _services.value = all.values.map { it.toEntry() }
    }

    // endregion

    // region Service CRUD

    /**
     * 创建新服务。
     *
     * 端口分配职责交给调用方 (通常 ViewModel 在打开弹窗时调用 [findNextPort] 预先算出端口)。
     *
     * @param name 显示名称 (唯一)
     * @param port 指定端口, 必传 (由调用方经 [findNextPort] 算出)
     * @return 创建的 ServiceEntry
     * @throws PortInUseException 端口被占用
     * @throws ServiceNameInUseException 名称已被其它服务占用 (重构后由本管理器校验, 替代原 Room unique index)
     */
    suspend fun createService(
        name: String,
        port: Int,
    ): ServiceEntry {
        val all = manifestStore.listAll()
        val usedPorts = all.values.map { it.port }.toSet()
        portManager.validatePort(port, usedPorts)

        // 重构后无 Room unique index 兜底, 这里显式校验名字唯一性
        val nameConflict = all.values.any { it.name == name }
        if (nameConflict) {
            throw ServiceNameInUseException(name)
        }

        val id = manifestStore.newServiceId()
        val manifest = ServiceManifest(
            id = id,
            name = name,
            port = port,
            enabled = false,
        )
        manifestStore.write(id, manifest)
        reload()
        return manifest.toEntry()
    }

    /**
     * 获取服务。
     */
    suspend fun getServiceById(id: String): ServiceEntry? {
        return manifestStore.read(id)?.toEntry()
    }

    /**
     * 在默认端口池中查找下一个可用端口 (不实际占用, 仅预览)。
     * @return 可用端口号; 端口池全部占用时返回 null
     */
    suspend fun findNextPort(): Int? {
        val usedPorts = manifestStore.listAll().values.map { it.port }.toSet()
        return portManager.findFreePort(usedPorts)
    }

    /** 默认端口池 (透传 [PortManager.portRange], 供 UI 展示)。 */
    val portRange: IntRange get() = portManager.portRange

    /**
     * 获取所有服务。
     */
    suspend fun getAllServices(): List<ServiceEntry> =
        manifestStore.listAll().values.map { it.toEntry() }

    /**
     * 删除服务。
     *
     * 先停止服务 (如果运行中), 再删除 manifest 文件。
     * 关联 scripts 数组随 manifest 一起删除, 无需单独清理。
     */
    suspend fun deleteService(id: String) = mutex.withLock {
        stopServiceInternal(id)
        manifestStore.delete(id)
        reload()
    }

    /**
     * 更新服务配置。
     *
     * 重构后无 Room unique index 兜底, 这里显式校验 name/port 唯一性 (排除自身)。
     *
     * @throws ServiceNameInUseException 名称被其它服务占用
     * @throws PortInUseException 端口被其它服务占用或被系统占用
     */
    suspend fun updateService(service: ServiceEntry) {
        val all = manifestStore.listAll()
        // 名字唯一性: 排除自身
        val nameConflict = all.values.any { it.id != service.id && it.name == service.name }
        if (nameConflict) {
            throw ServiceNameInUseException(service.name)
        }
        // 端口唯一性: 排除自身
        val portConflict = all.values.any { it.id != service.id && it.port == service.port }
        if (portConflict) {
            throw PortInUseException("Port ${service.port} already assigned to another service")
        }
        manifestStore.update(service.id) {
            it.copy(name = service.name, port = service.port, enabled = service.enabled)
        }
        reload()
    }

    // endregion

    // region 脚本管理

    /**
     * 添加脚本到服务。
     *
     * @param serviceId 服务 ID
     * @param namespace 脚本 namespace
     * @param scriptCode 脚本源码 (存入 scriptCodes 供启动时使用)
     * @param enabled 是否启用 (默认 true)
     */
    suspend fun addScriptToService(
        serviceId: String,
        namespace: String,
        scriptCode: String,
        enabled: Boolean = true,
    ) = mutex.withLock {
        scriptCodes[namespace] = scriptCode
        manifestStore.update(serviceId) { m ->
            val refs = m.scripts.toMutableList()
            refs.removeAll { it.namespace == namespace }
            refs.add(ServiceManifest.ScriptRef(namespace, enabled))
            m.copy(scripts = refs)
        }

        // 如果服务正在运行, 重新注册工具并通知
        val svcInstance = activeServices[serviceId]
        if (svcInstance != null && enabled) {
            rebuildTools(svcInstance, serviceId)
            svcInstance.notifyToolsListChanged()
        }
    }

    /**
     * 从服务移除脚本。
     */
    suspend fun removeScriptFromService(
        serviceId: String,
        namespace: String,
    ) = mutex.withLock {
        manifestStore.update(serviceId) { m ->
            m.copy(scripts = m.scripts.filterNot { it.namespace == namespace })
        }

        val svcInstance = activeServices[serviceId]
        if (svcInstance != null) {
            rebuildTools(svcInstance, serviceId)
            svcInstance.notifyToolsListChanged()
        }
    }

    /**
     * 切换脚本在服务中的启用状态 (工具白名单)。
     */
    suspend fun toggleScriptEnabled(
        serviceId: String,
        namespace: String,
        enabled: Boolean,
    ) = mutex.withLock {
        manifestStore.update(serviceId) { m ->
            m.copy(scripts = m.scripts.map {
                if (it.namespace == namespace) it.copy(enabled = enabled) else it
            })
        }

        val svcInstance = activeServices[serviceId]
        if (svcInstance != null) {
            rebuildTools(svcInstance, serviceId)
            svcInstance.notifyToolsListChanged()
        }
    }

    /**
     * 获取服务包含的脚本引用列表。
     */
    suspend fun getServiceScripts(serviceId: String): List<ServiceManifest.ScriptRef> {
        return manifestStore.read(serviceId)?.scripts ?: emptyList()
    }

    // endregion

    // region 服务启动/停止

    /**
     * 启动服务。
     *
     * @throws PortInUseException 端口被占用
     * @throws com.dokar.quickjs.QuickJsException 脚本 evaluate 失败
     */
    suspend fun startService(serviceId: String) = mutex.withLock {
        startServiceInternal(serviceId)
    }

    /**
     * 启动服务的内部实现 (不加锁, 供已持锁的方法调用)。
     */
    private suspend fun startServiceInternal(serviceId: String) {
        if (activeServices.containsKey(serviceId)) return

        val manifest = manifestStore.read(serviceId)
            ?: throw IllegalStateException("Service not found: $serviceId")

        // 启动前预检端口可用性
        if (!portManager.isPortAvailable(manifest.port)) {
            throw PortInUseException("Port ${manifest.port} is in use")
        }

        val refs = manifest.scripts
        val enabledRefs = refs.filter { it.enabled }

        // 确保脚本代码已加载 (app 重启后 scriptCodes 为空)
        enabledRefs.forEach { ensureCodeLoaded(it.namespace) }

        // TODO: 从 ScriptManifest.metadata.instructions 字段拼接各脚本的 @instructions。
        // 当前 ServiceManager 不直接持有 ScriptManifestStore, 需要后续注入或通过
        // codeLoader 扩展返回 manifest。先用 null 占位 (与原 ProfileManager placeholder 行为一致)。
        val instructions: String? = null

        val entry = manifest.toEntry()

        val svcInstance = try {
            serverFactory.create(
                service = entry,
                scriptRefs = refs,
                scriptCodes = scriptCodes,
                runtimeConfigs = enabledRefs.associate { it.namespace to runtimeConfigLoader(it.namespace) },
                instructions = instructions,
            )
        } catch (e: Exception) {
            // 释放已 acquire 的 runtime 引用 (对未 acquire 的 ns 是 no-op)。
            // NonCancellable: 调用方协程被取消时也必须完成释放, 否则引用计数泄漏,
            // 后续 acquire 会误复用/误销毁 runtime。
            withContext(NonCancellable) {
                for (ns in enabledRefs.map { it.namespace }) {
                    runtimeManager.release(ns)
                }
            }
            throw e
        }

        activeServices[serviceId] = svcInstance
        manifestStore.update(serviceId) { it.copy(enabled = true) }
        reload()
        logManager?.system("Service '${entry.name}' started on :${entry.port} (${svcInstance.registeredTools.size} tools)")
    }

    /**
     * 停止服务。
     *
     * 即使 manifest 文件已被删除 (例如 deleteService 先跑了一半), 也要保证 runtime 引用被释放,
     * 不会因 manifestStore.update 找不到文件而抛异常中断清理。
     */
    suspend fun stopService(serviceId: String) = mutex.withLock {
        val manifest = manifestStore.read(serviceId)
        stopServiceInternal(serviceId)
        // manifest 可能已被并发删除, update 找不到文件时跳过 enabled=false 写入
        if (manifest != null) {
            runCatching { manifestStore.update(serviceId) { it.copy(enabled = false) } }
        }
        reload()
        logManager?.system("Service '${manifest?.name ?: serviceId}' stopped")
    }

    /**
     * 脚本代码变更后, 重启所有正在运行该脚本的服务, 使新代码生效。
     *
     * 先全部停止 → 再全部启动, 保证 "全部停止 → 全部启动" 原子执行。
     * RuntimeManager 引用计数随 stop/release 归零后, acquire 重新 evaluate 新代码。
     *
     * @param namespace 脚本 namespace
     * @return 成功重启的服务数量
     */
    suspend fun restartServicesForScript(namespace: String): Int = mutex.withLock {
        val idsToRestart = activeServices.entries
            .filter { it.value.scriptNamespaces.contains(namespace) }
            .map { it.key }

        if (idsToRestart.isEmpty()) return@withLock 0

        for (id in idsToRestart) {
            stopServiceInternal(id)
        }

        var restarted = 0
        for (id in idsToRestart) {
            try {
                startServiceInternal(id)
                restarted++
            } catch (e: Exception) {
                logManager?.system("Failed to restart service $id after script '$namespace' update: ${e.message}")
            }
        }
        logManager?.system("Restarted $restarted service(s) for script '$namespace'")
        restarted
    }

    /**
     * 重启单个服务 (stop + start)。
     *
     * 适用场景: 服务异常/卡死需要重启, 且不涉及脚本代码变更。
     * 若需在脚本代码变更后重新加载, 请使用 [restartServicesForScript]。
     *
     * @return true 表示成功重启, false 表示服务原本未运行
     */
    suspend fun restartService(serviceId: String): Boolean = mutex.withLock {
        if (!activeServices.containsKey(serviceId)) return@withLock false
        stopServiceInternal(serviceId)
        try {
            startServiceInternal(serviceId)
            true
        } catch (e: Exception) {
            logManager?.system("Failed to restart service $serviceId: ${e.message}")
            false
        }
    }

    /**
     * 恢复所有 enabled=true 的服务 (前台服务重启时调用)。
     */
    suspend fun restoreEnabledServices(): Int {
        val enabledManifests = manifestStore.listAll().values.filter { it.enabled }
        var restored = 0
        for (m in enabledManifests) {
            try {
                startService(m.id)
                restored++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return restored
    }

    /**
     * 销毁所有服务 (app 退出时调用)。
     */
    suspend fun destroyAll() = mutex.withLock {
        for ((_, svcInstance) in activeServices) {
            svcInstance.stop()
            for (ns in svcInstance.scriptNamespaces) {
                runtimeManager.release(ns)
            }
        }
        activeServices.clear()
    }

    /** 获取活跃服务数量。 */
    fun activeServiceCount(): Int = activeServices.size

    /** 查找运行中的服务 (供调试/UI 用)。 */
    fun getService(serviceId: String): McpServiceInstance? = activeServices[serviceId]

    // endregion

    // region 内部辅助

    /**
     * 停止服务 (不加锁, 供已持锁的方法调用)。
     */
    private suspend fun stopServiceInternal(serviceId: String) {
        val svcInstance = activeServices.remove(serviceId) ?: return
        try {
            svcInstance.stop()
        } finally {
            // 无论 Ktor stop 是否异常, 都必须释放 runtime 引用, 否则重启会复用已损坏的 runtime
            for (ns in svcInstance.scriptNamespaces) {
                runtimeManager.release(ns)
            }
        }
    }

    /**
     * 重建服务的工具注册。
     *
     * 旧工具全部 removeTool, 新工具重新 addTool。
     * 用于脚本增删/勾选变化时。
     */
    private suspend fun rebuildTools(svcInstance: McpServiceInstance, serviceId: String) {
        val manifest = manifestStore.read(serviceId) ?: return
        val refs = manifest.scripts

        // 移除所有旧工具
        for (toolName in svcInstance.registeredTools.toList()) {
            svcInstance.mcpServer.removeTool(toolName)
            svcInstance.registeredTools.remove(toolName)
        }

        // 重新注册启用的脚本工具
        val newNamespaces = mutableSetOf<String>()
        for (ref in refs) {
            if (!ref.enabled) continue
            ensureCodeLoaded(ref.namespace)
            val code = scriptCodes[ref.namespace] ?: continue

            // 确保 runtime 存在
            runtimeManager.acquire(ref.namespace, code, runtimeConfigLoader(ref.namespace))
            newNamespaces.add(ref.namespace)

            val runtime = runtimeManager.getRuntime(ref.namespace)
            if (runtime != null) {
                for ((localName, toolDef) in runtime.toolRegistry) {
                    val fullName = "${ref.namespace}_$localName"
                    serverFactory.registerTool(
                        svcInstance.mcpServer, fullName, toolDef,
                        ref.namespace, localName,
                    )
                    svcInstance.registeredTools.add(fullName)
                }
            }
        }

        // 释放不再引用的 runtime
        val removed = svcInstance.scriptNamespaces - newNamespaces
        for (ns in removed) {
            runtimeManager.release(ns)
        }
        svcInstance.scriptNamespaces.clear()
        svcInstance.scriptNamespaces.addAll(newNamespaces)
    }

    // endregion
}

/** ServiceManifest → 内存读模 ServiceEntry。 */
private fun ServiceManifest.toEntry(): ServiceEntry = ServiceEntry(
    id = id,
    name = name,
    port = port,
    enabled = enabled,
    autoCreated = autoCreated,
    createdAt = createdAt,
)