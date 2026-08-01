package io.github.totomika.pocketmcp.script

import io.github.totomika.pocketmcp.data.log.LogDao
import io.github.totomika.pocketmcp.mcp.ServiceManager
import io.github.totomika.pocketmcp.mcp.ServiceManifestStore
import io.github.totomika.pocketmcp.permission.PermissionDeclaration
import io.github.totomika.pocketmcp.permission.PermissionManager

/**
 * 脚本管理器。
 *
 * 统一封装脚本的导入、更新、卸载流程。
 *
 * 重构后: 元数据 / 权限 走 [ScriptManifestStore] (单文件), 不再依赖 Room;
 * Service 关联清理走 [ServiceManifestStore] + [ServiceManager.restartServicesForScript];
 * 日志清理走 [LogDao.deleteByNamespace]。
 *
 * 见 docs/08-distribution.md。
 *
 * 流程:
 * 1. 导入: 解析元数据 → 校验 → 存储代码 → 写 manifest → 导入权限 → 注册脚本代码缓存
 *    (不自动创建 per-script 服务, 用户通过"新建服务"弹窗选择脚本组合)
 * 2. 更新: 版本对比 → 权限 syncOnUpdate → 替换代码+manifest → reload 受影响 service
 * 3. 卸载: 从所有 service 的 manifest 移除引用 → reload → 删 manifest → 删代码 → 可选删数据/日志
 */
class ScriptManager(
    private val repository: ScriptRepository,
    private val manifestStore: ScriptManifestStore,
    private val serviceManifestStore: ServiceManifestStore,
    private val permissionManager: PermissionManager,
    private val serviceManager: ServiceManager,
    private val logDao: LogDao,
    private val urlImporter: UrlImporter = UrlImporter(),
) {

    /**
     * 导入脚本结果。
     */
    sealed class ImportResult {
        /** 新导入成功 */
        data class Imported(val entry: ScriptEntry) : ImportResult()

        /** 同 namespace 已存在, 需要用户确认更新 */
        data class UpdateAvailable(
            val existing: ScriptEntry,
            val newVersion: String,
            val newPermissions: List<PermissionDeclaration>,
        ) : ImportResult()

        /** 同版本已安装 */
        data class SameVersion(val existing: ScriptEntry) : ImportResult()

        /** 已安装更新版本 */
        data class OlderVersion(val existing: ScriptEntry, val attemptedVersion: String) :
            ImportResult()

        /** 导入失败 */
        data class Error(val message: String) : ImportResult()
    }

    /**
     * 导入脚本 (新导入或检测更新)。
     *
     * @param code 脚本源码
     * @param sourceType 导入渠道
     * @param sourceUrl URL 导入的源地址 (仅 URL 类型)
     * @return 导入结果
     */
    suspend fun importScript(
        code: String,
        sourceType: ScriptSourceType,
        sourceUrl: String? = null,
    ): ImportResult {
        // 1. 解析并校验元数据
        val metadata = try {
            ScriptMetadataParser.parseAndValidate(code)
        } catch (e: IllegalArgumentException) {
            return ImportResult.Error(e.message ?: "Invalid metadata")
        }

        // 2. 检查是否已存在同 namespace (以 manifest 是否存在为准)
        val existingManifest = manifestStore.read(metadata.namespace)
        if (existingManifest != null) {
            val existing = existingManifest.metadata.toEntry()
            return when {
                VersionUtils.isSame(metadata.version, existing.version) -> {
                    ImportResult.SameVersion(existing)
                }

                VersionUtils.isOlder(metadata.version, existing.version) -> {
                    ImportResult.OlderVersion(existing, metadata.version)
                }

                else -> {
                    // 新版本, 需要用户确认更新 (权限 syncOnUpdate 已写入清单)
                    val newPermissions = permissionManager.syncOnUpdate(metadata.namespace, code)
                    ImportResult.UpdateAvailable(existing, metadata.version, newPermissions)
                }
            }
        }

        // 3. 新导入: 存储代码 + 写 manifest
        repository.storeScriptCode(metadata.namespace, code)
        val now = System.currentTimeMillis()
        val newManifest = ScriptManifest(
            metadata = ScriptManifest.Metadata(
                namespace = metadata.namespace,
                name = metadata.name,
                scriptVersion = metadata.version,
                description = metadata.description,
                author = metadata.author,
                instructions = metadata.instructions,
                homepage = metadata.homepage,
                minAppVersion = metadata.minAppVersion,
                sourceType = sourceType.name,
                sourceUrl = sourceUrl,
                importedAt = now,
                updatedAt = now,
            ),
            permissions = emptyList(),
        )
        manifestStore.write(metadata.namespace, newManifest)
        val savedEntry = newManifest.metadata.toEntry()

        // 4. 导入权限声明 (默认 granted=false)
        permissionManager.importPermissions(metadata.namespace, code)

        // 5. 注册脚本代码到 ServiceManager 代码缓存 (供后续手动新建服务时使用)
        serviceManager.registerScriptCode(metadata.namespace, code)

        return ImportResult.Imported(savedEntry)
    }

    /**
     * 确认更新脚本 (用户确认后调用)。
     *
     * @param namespace 脚本 namespace
     * @param newCode 新版本脚本源码
     * @param sourceUrl URL 导入的源地址 (可选)
     */
    suspend fun confirmUpdate(
        namespace: String,
        newCode: String,
        sourceUrl: String? = null,
    ): ScriptEntry {
        val metadata = ScriptMetadataParser.parseAndValidate(newCode)
        val existingManifest = manifestStore.read(namespace)
            ?: throw IllegalStateException("Script not found: $namespace")
        val existing = existingManifest.metadata

        // 1. 替换代码文件
        repository.storeScriptCode(namespace, newCode)

        // 2. 更新 manifest 元数据 (permissions 字段已在 importScript 阶段 syncOnUpdate 写过)
        val updated = existing.copy(
            name = metadata.name,
            scriptVersion = metadata.version,
            description = metadata.description,
            author = metadata.author,
            instructions = metadata.instructions,
            homepage = metadata.homepage,
            minAppVersion = metadata.minAppVersion,
            sourceUrl = sourceUrl ?: existing.sourceUrl,
            updatedAt = System.currentTimeMillis(),
        )
        manifestStore.update(namespace) { it.copy(metadata = updated) }

        // 3. 用户确认后 grant 新增权限由 UI 流程调 permissionManager.grant (这里不重复)

        // 4. 更新 ServiceManager 代码缓存
        serviceManager.registerScriptCode(namespace, newCode)

        // 5. 重启运行该脚本的服务, 让新代码生效 (refcount 归零 → 重新 evaluate)
        serviceManager.restartServicesForScript(namespace)

        return updated.toEntry()
    }

    /**
     * 卸载脚本。
     *
     * 卸载 = 删脚本身份 (manifest + 源码) 不可逆; 数据 / 日志可选删除。
     *
     * 顺序:
     * 1. 从所有 services/<svcId>/manifest.json 移除引用此 ns 的项 → 返回受影响服务 id
     * 2. 重启受影响的服务 (停 + 启, 应用新工具集合, runtime 引用同步释放)
     * 3. 释放脚本代码缓存
     * 4. 删 manifest + 源码 (卸载必然项)
     * 5. 可选: 删运行时数据 (KV/SQL/private/external)
     * 6. 可选: 删相关日志
     *
     * 修复了原 bug: 早退分支 (`? : return`) 不再漏清权限 — 此处以 manifest 是否存在为
     * 唯一判据, 没有 manifest 就视为未安装, 自然没有 permissions 需要清。
     *
     * 修复了原 bug: profile_scripts 残留 — 不再依赖 Room CASCADE (CASCADE 只在删 service
     * 时触发, 删 script 时不触发), 改为明确扫描 services/<svcId>/manifest.json 移除引用。
     *
     * @param namespace 脚本 namespace
     * @param purgeData 是否同时删除数据 (KV, SQL, private 文件)
     * @param purgeLogs 是否同时删除此脚本相关日志
     */
    /**
     * @return 成功重启的服务数量 (0 = 无受影响服务或无运行中服务)
     */
    suspend fun uninstallScript(
        namespace: String,
        purgeData: Boolean = false,
        purgeLogs: Boolean = false,
    ): Int {
        // 已卸载则直接返回 (manifest 不存在 = 未安装)
        if (!manifestStore.exists(namespace)) return 0

        // 1. 从所有 service manifest 移除引用此 ns 的项, 返回受影响服务
        val affected = serviceManifestStore.removeScriptFromAllServices(namespace)

        // 2. 重启受影响的服务 (停+启, 应用新工具集合 + runtime 引用释放)
        // 注: 即使没有运行中的服务, restartServicesForScript 也是 no-op 安全的。
        val restarted = if (affected.isNotEmpty()) {
            serviceManager.restartServicesForScript(namespace)
        } else 0

        // 3. 清理代码缓存
        serviceManager.removeScriptCode(namespace)

        // 4. 删清单 + 源码
        manifestStore.delete(namespace)
        repository.deleteScriptCode(namespace)

        // 5. 可选: 删运行时数据
        if (purgeData) {
            repository.deleteScriptData(namespace)
        }

        // 6. 可选: 删日志
        if (purgeLogs) {
            logDao.deleteByNamespace(namespace)
        }

        // 7. 清理残留空目录 (manifest + src + [data] 全删后可能遗留 <ns>/ 空目录)
        repository.deleteScriptDirIfEmpty(namespace)

        return restarted
    }

    /**
     * 从 URL 导入脚本。
     *
     * @param url 脚本 URL
     * @return 导入结果
     */
    suspend fun importFromUrl(url: String): ImportResult {
        return try {
            val code = urlImporter.fetch(url)
            importScript(code, ScriptSourceType.URL, url)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "URL import failed")
        }
    }

    /**
     * 检查 URL 导入脚本的更新。
     *
     * 遍历所有 URL 导入的脚本, fetch 对比版本。
     * 不自动安装, 仅返回有更新的脚本列表。
     *
     * @return 有更新的脚本列表 (namespace → 新版本号)
     */
    suspend fun checkUrlUpdates(): List<UrlUpdateInfo> {
        val urlScripts = manifestStore.listAll().values
            .filter { it.metadata.sourceType == ScriptSourceType.URL.name }
        val updates = mutableListOf<UrlUpdateInfo>()

        for (manifest in urlScripts) {
            val ns = manifest.metadata.namespace
            val url = manifest.metadata.sourceUrl ?: continue
            val currentVersion = manifest.metadata.scriptVersion
            try {
                val code = urlImporter.fetch(url)
                val metadata = ScriptMetadataParser.parse(code) ?: continue

                if (VersionUtils.isNewer(metadata.version, currentVersion)) {
                    updates.add(
                        UrlUpdateInfo(
                            namespace = ns,
                            currentVersion = currentVersion,
                            newVersion = metadata.version,
                            newCode = code,
                        )
                    )
                }
            } catch (e: Exception) {
                // 网络错误, 跳过此脚本
            }
        }

        return updates
    }

    /**
     * 获取所有脚本 (按 namespace 升序)。
     */
    suspend fun getAllScripts(): List<ScriptEntry> =
        manifestStore.listAll().values.map { it.metadata.toEntry() }.sortedBy { it.namespace }

    /**
     * 获取脚本元数据。
     */
    suspend fun getScript(namespace: String): ScriptEntry? =
        manifestStore.read(namespace)?.metadata?.toEntry()

    /**
     * 读取脚本代码。
     */
    fun readScriptCode(namespace: String): String? = repository.readScriptCode(namespace)
}

/**
 * URL 更新信息。
 */
data class UrlUpdateInfo(
    val namespace: String,
    val currentVersion: String,
    val newVersion: String,
    val newCode: String,
)

/** manifest metadata → 内存读模 ScriptEntry (兼容旧调用方, 不带 id)。 */
private fun ScriptManifest.Metadata.toEntry(): ScriptEntry = ScriptEntry(
    namespace = namespace,
    name = name,
    version = scriptVersion,
    description = description,
    author = author,
    instructions = instructions,
    homepage = homepage,
    minAppVersion = minAppVersion,
    sourceType = sourceType,
    sourceUrl = sourceUrl,
    importedAt = importedAt,
    updatedAt = updatedAt,
)
