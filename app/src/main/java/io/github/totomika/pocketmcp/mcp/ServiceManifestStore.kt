package io.github.totomika.pocketmcp.mcp

import io.github.totomika.pocketmcp.data.fs.FsPathManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/**
 * 服务清单 (manifest) 文件存储。
 *
 * 每个服务一个清单文件: `files/services/<svcId>/manifest.json`。
 * 服务身份 `<svcId>` = 短 UUID (8 字符 base62), 跨设备稳定, 端口可改不影响身份。
 *
 * ## 与 [io.github.totomika.pocketmcp.script.ScriptManifestStore] 同风格
 * - 原子写 (.tmp → rename)
 * - per-svcId 锁
 * - listAll 扫目录得事实
 *
 * ## 跨服务操作
 * [removeScriptFromAllServices] 是卸载脚本的关键 hook:
 * 扫所有 `services/<svcId>/manifest.json`, 移除引用此 ns 的项, 返回受影响的服务 id 列表,
 * 上层据此触发对应 service 热重载 (修正原 Room CASCADE 只在删 service 时触发、删脚本时却残留的 bug)。
 */
class ServiceManifestStore(
    private val pathManager: FsPathManager,
    private val json: Json = defaultJson,
) {
    /** per-svcId 写锁。 */
    private val locks = mutableMapOf<String, Mutex>()

    /**
     * 读取服务清单。不存在或失败返回 null。
     */
    suspend fun read(svcId: String): ServiceManifest? = lockFor(svcId).withLock {
        val file = pathManager.serviceManifestFile(svcId)
        if (!file.exists()) return@withLock null
        runCatching {
            json.decodeFromString(ServiceManifest.serializer(), file.readText())
        }.getOrNull()
    }

    /**
     * 写入清单 (原子重写)。目录不存在时自动 mkdirs。
     */
    suspend fun write(svcId: String, manifest: ServiceManifest) = lockFor(svcId).withLock {
        writeInternal(svcId, manifest)
    }

    /**
     * 更新: 读 → 改 → 写, 全程持锁。
     */
    suspend fun update(svcId: String, block: (ServiceManifest) -> ServiceManifest) {
        lockFor(svcId).withLock {
            val file = pathManager.serviceManifestFile(svcId)
            val current = if (file.exists()) {
                runCatching {
                    json.decodeFromString(ServiceManifest.serializer(), file.readText())
                }.getOrNull()
            } else null
            val next = block(current ?: throw NoSuchServiceException(svcId))
            writeInternal(svcId, next)
        }
    }

    /**
     * 删除服务清单 (卸载服务时调用)。返回是否确实删除了某个文件。
     */
    suspend fun delete(svcId: String): Boolean = lockFor(svcId).withLock {
        val file = pathManager.serviceManifestFile(svcId)
        val deleted = file.delete()
        // 顺手清空目录
        if (deleted) pathManager.serviceDir(svcId).delete()
        deleted
    }

    /**
     * 判定清单是否存在。
     */
    fun exists(svcId: String): Boolean = pathManager.serviceManifestFile(svcId).exists()

    /**
     * 列出所有服务 (扫 services/ 目录)。
     */
    suspend fun listAll(): Map<String, ServiceManifest> {
        val dir = pathManager.servicesRootDir()
        if (!dir.exists()) return emptyMap()
        val out = sortedMapOf<String, ServiceManifest>()
        for (sub in dir.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            val id = sub.name
            val file = pathManager.serviceManifestFile(id)
            if (!file.exists()) continue
            runCatching {
                json.decodeFromString(ServiceManifest.serializer(), file.readText())
            }.onSuccess { out[id] = it }
        }
        return out
    }

    /**
     * 卸载脚本的关键 hook: 扫所有 services/<svcId>/manifest.json, 移除引用此 namespace 的项。
     *
     * @param namespace 要移除的脚本 namespace
     * @return 受影响的服务 id 列表 (上层需据此热重载这些 service)
     */
    suspend fun removeScriptFromAllServices(namespace: String): List<String> {
        val dir = pathManager.servicesRootDir()
        if (!dir.exists()) return emptyList()

        val affected = mutableListOf<String>()
        for (sub in dir.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            val id = sub.name
            lockFor(id).withLock {
                val file = pathManager.serviceManifestFile(id)
                if (!file.exists()) return@withLock
                val current = runCatching {
                    json.decodeFromString(ServiceManifest.serializer(), file.readText())
                }.getOrNull() ?: return@withLock

                if (current.scripts.none { it.namespace == namespace }) return@withLock
                val updated =
                    current.copy(scripts = current.scripts.filterNot { it.namespace == namespace })
                writeInternal(id, updated)
                affected.add(id)
            }
        }
        return affected
    }

    /**
     * 生成新的短服务 ID (8 字符 base62, 形如 "a3f9c1Z2")。
     * 重复概率可忽略 (~62^8 ≈ 2.18e14); 调用方在冲突时可重试。
     */
    fun newServiceId(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        val sb = StringBuilder(ID_LENGTH)
        repeat(ID_LENGTH) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private fun writeInternal(svcId: String, manifest: ServiceManifest) {
        val file = pathManager.serviceManifestFile(svcId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        tmp.writeText(json.encodeToString(ServiceManifest.serializer(), manifest))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun lockFor(svcId: String): Mutex =
        synchronized(locks) { locks.getOrPut(svcId) { Mutex() } }

    companion object {
        private const val TMP_SUFFIX = ".tmp"
        private const val ID_LENGTH = 8

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

/** 服务清单不存在 (已删除 / 从未有) 时抛。 */
class NoSuchServiceException(val serviceId: String) :
    RuntimeException("Service manifest not found for id='$serviceId'")