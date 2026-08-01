package io.github.totomika.pocketmcp.script

import io.github.totomika.pocketmcp.data.fs.FsPathManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 脚本清单 (manifest) 文件存储。
 *
 * 每个脚本一个清单文件: `files/scripts/<ns>/manifest.json`。
 * 把元数据 + 权限声明 + 授权状态合并到单文件 (模板见 [ScriptManifest])。
 *
 * ## 写入原子性
 * 写时先存到 `<file>.tmp`, 再 `renameTo` 替换, 避免中途崩溃导致 JSON 截断。
 * 读到不完整 / 缺失的 manifest 直接抛 [NoSuchScriptException], 上层据此视为"未安装"。
 *
 * ## 并发
 * 每个 namespace 一把 [Mutex], 防止同脚本的并发写互相覆盖。
 * 不同 namespace 之间互不阻塞 (持有不同锁)。
 *
 * 与 [io.github.totomika.pocketmcp.host.KvApi] / [io.github.totomika.pocketmcp.host.SqlApi]
 * 同为"per-namespace 隔离存储"风格, 但 target 是 app 私有管理数据, 脚本不应直接读写。
 */
class ScriptManifestStore(
    private val pathManager: FsPathManager,
    private val json: Json = defaultJson,
) {
    /** per-namespace 写锁 (lazy 创建, 读时也用同一把锁防止读到中间态)。 */
    private val locks = mutableMapOf<String, Mutex>()

    /**
     * 读取脚本清单。文件不存在或反序列化失败时返回 null (上层据此判定"未安装")。
     */
    suspend fun read(namespace: String): ScriptManifest? = lockFor(namespace).withLock {
        readUnlocked(namespace)
    }

    /**
     * 同步读取 (不加锁)。用于非 suspend 上下文 (如 ServiceManager 的 configLoader)。
     * 文件写入用原子 rename, 不加锁读取到的也是完整 JSON, 不会读到中间态。
     */
    fun readSync(namespace: String): ScriptManifest? = readUnlocked(namespace)

    private fun readUnlocked(namespace: String): ScriptManifest? {
        val file = pathManager.scriptManifestFile(namespace)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(ScriptManifest.serializer(), file.readText())
        }.getOrNull()
    }

    /**
     * 写入脚本清单 (原子重写)。
     * 目录不存在时自动 mkdirs。
     */
    suspend fun write(namespace: String, manifest: ScriptManifest) = lockFor(namespace).withLock {
        val file = pathManager.scriptManifestFile(namespace)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        tmp.writeText(json.encodeToString(ScriptManifest.serializer(), manifest))
        if (!tmp.renameTo(file)) {
            // rename 失败兜底: 直接写目标 (rename 在某些 fs 上失败但 copy 成功)
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * 更新清单的便捷方法: 读 → 改 → 写, 全程持锁, 单事务语义。
     */
    suspend fun update(namespace: String, block: (ScriptManifest) -> ScriptManifest) {
        lockFor(namespace).withLock {
            val file = pathManager.scriptManifestFile(namespace)
            val current = if (file.exists()) {
                runCatching {
                    json.decodeFromString(ScriptManifest.serializer(), file.readText())
                }.getOrNull()
            } else null
            val next = block(current ?: throw NoSuchScriptException(namespace))
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            tmp.writeText(json.encodeToString(ScriptManifest.serializer(), next))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * 删除脚本清单 (连同空目录)。卸载流程调用。
     */
    suspend fun delete(namespace: String) = lockFor(namespace).withLock {
        pathManager.scriptManifestFile(namespace).delete()
    }

    /**
     * 判定清单是否存在。
     */
    fun exists(namespace: String): Boolean = pathManager.scriptManifestFile(namespace).exists()

    /**
     * 列出所有已安装脚本 (扫描 scripts/ 目录)。
     *
     * 以"文件系统事实"为已安装判据: 没有元数据表, 看目录中有 manifest.json 的子目录即视为已安装。
     * 读取失败的清单跳过并记录, 不影响整体收集。
     *
     * @return namespace → ScriptManifest 的有序映射 (按 namespace 升序)。
     */
    suspend fun listAll(): Map<String, ScriptManifest> {
        val dir = pathManager.scriptsRootDir()
        if (!dir.exists()) return emptyMap()

        val out = sortedMapOf<String, ScriptManifest>()
        for (sub in dir.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            val ns = sub.name
            val file = pathManager.scriptManifestFile(ns)
            if (!file.exists()) continue
            runCatching {
                json.decodeFromString(ScriptManifest.serializer(), file.readText())
            }.onSuccess { manifest ->
                out[ns] = manifest
            }
        }
        return out
    }

    private fun lockFor(namespace: String): Mutex =
        synchronized(locks) { locks.getOrPut(namespace) { Mutex() } }

    companion object {
        private const val TMP_SUFFIX = ".tmp"

        /** 默认 JSON 配置: 容许未知字段 (向前兼容), 缩进美化便于人工调试。 */
        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

/** 脚本清单不存在 (未安装 / 已卸载) 时抛。 */
class NoSuchScriptException(val namespace: String) :
    RuntimeException("Script manifest not found for namespace='$namespace'")