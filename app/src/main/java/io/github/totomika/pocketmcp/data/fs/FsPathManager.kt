package io.github.totomika.pocketmcp.data.fs

import android.content.Context
import java.io.File

/**
 * 文件系统路径管理。
 *
 * 管理 host.fs.private / host.fs.external / host.fs.shared 的根路径。
 *
 * ## 内部目录布局 (app 私有, 用户不可见)
 * - src:       files/scripts/<namespace>/src/script.js       (脚本代码, 只读)
 * - manifest:  files/scripts/<namespace>/manifest.json      (脚本清单: 元数据 + 权限)
 * - data:       files/scripts/<namespace>/data/               (运行时数据)
 * - fs:         files/scripts/<namespace>/data/fs/             (host.fs.private 根)
 * - kv:         files/scripts/<namespace>/data/kv/           (KV 存储)
 * - sql:        files/scripts/<namespace>/data/sql/          (host.sql 独立 DB)
 *
 * ## 服务目录 (app 私有)
 * - manifest:  files/services/<svcId>/manifest.json         (服务清单: port/enabled/scripts[])
 * 其中 <svcId> 为短 UUID, 服务身份稳定标识 (端口可改不影响身份)。
 *
 * ## external 目录布局 (用户可见, 文件管理器可访问)
 * - external: .../Android/data/<pn>/files/scripts-external/<namespace>/      (host.fs.external 根)
 *
 * ## shared
 * - shared: ~ (设备外部存储根目录, 由 Environment.getExternalStorageDirectory() 确定)
 */
class FsPathManager(private val context: Context) {

    // region 脚本内部目录布局 (scripts/<namespace>/)

    /** 所有脚本根目录: files/scripts/ (扫此目录得全部已安装 namespace)。 */
    fun scriptsRootDir(): File {
        return File(context.filesDir, SCRIPTS_ROOT)
    }

    /** 脚本根目录: files/scripts/<namespace>/ */
    fun scriptDir(namespace: String): File {
        return File(scriptsRootDir(), namespace)
    }

    /** 脚本清单文件: files/scripts/<namespace>/manifest.json (元数据 + 权限) */
    fun scriptManifestFile(namespace: String): File {
        return File(scriptDir(namespace), FILE_MANIFEST)
    }

    /** 代码目录: files/scripts/<namespace>/src/ */
    fun scriptSrcDir(namespace: String): File {
        return File(scriptDir(namespace), DIR_SRC)
    }

    /** 数据目录: files/scripts/<namespace>/data/ */
    fun scriptDataDir(namespace: String): File {
        return File(scriptDir(namespace), DIR_DATA)
    }

    /** KV 数据库目录: files/scripts/<namespace>/data/kv/ */
    fun kvDir(namespace: String): File {
        return File(scriptDataDir(namespace), DIR_KV)
    }

    /** SQL 数据库目录: files/scripts/<namespace>/data/sql/ */
    fun sqlDir(namespace: String): File {
        return File(scriptDataDir(namespace), DIR_SQL)
    }

    // endregion

    // region 服务目录布局 (services/<svc-id>/)

    /** 所有服务的根目录: files/services/ (扫此目录得全部服务 id)。 */
    fun servicesRootDir(): File {
        return File(context.filesDir, SERVICES_ROOT).apply { mkdirs() }
    }

    /** 服务根目录: files/services/<svcId>/ */
    fun serviceDir(svcId: String): File {
        return File(servicesRootDir(), svcId)
    }

    /** 服务清单文件: files/services/<svcId>/manifest.json */
    fun serviceManifestFile(svcId: String): File {
        return File(serviceDir(svcId), FILE_MANIFEST)
    }

    // endregion

    // region host.fs 路径

    /**
     * 获取 private 根目录 (app 私有, 自动创建)。
     *
     * 落点: files/scripts/<namespace>/data/fs/
     */
    fun privateRoot(namespace: String): File {
        return File(scriptDataDir(namespace), DIR_FS).apply { mkdirs() }
    }

    /**
     * 获取 external 根目录 (用户可见的外存区, 自动创建)。
     *
     * 落点: .../Android/data/<pn>/files/scripts-external/<namespace>/
     *
     * 故意不复用 private 的分层 ——
     * private 的分层是 app 内部组织 (并存 src/kv/sql/fs 等多种资源),
     * external 只面向用户可见的脚本文件, 套用 private 结构是不必要的。
     */
    fun externalRoot(namespace: String): File {
        val base = context.getExternalFilesDir(null)
            ?: error("getExternalFilesDir(null) returned null")
        return File(base, "$DIR_EXTERNAL/$namespace").apply { mkdirs() }
    }

    /**
     * 共享存储根路径 (~ 的展开)。
     */
    val sharedRoot: File
        get() = android.os.Environment.getExternalStorageDirectory()

    /**
     * 解析 shared 路径为 [File]。
     *
     * 仅接受两种形态的入参:
     * - `~` 或 `~/` 后接相对路径: 相对于 [sharedRoot] (设备外部存储根目录)。
     * - 绝对路径 (如 `/storage/emulated/0/...` 或 `/etc/passwd`): 原样使用。
     *
     * SECURE BY REJECTION:
     * 不含 `~` 前缀又非绝对路径的字符串 (如 "foo/bar") 会被**拒绝** ——
     * 旧实现把它们当相对工作目录解析, 落点不可预知 (会进 `/data/data/<app>/foo/bar`),
     * 对脚本是灾难。要求显式 `~` 或 `/` 前缀, 让"在 sdcard 上相对"与"绝对路径"
     * 两种意图可区分, 消除歧义。
     *
     * @throws IllegalArgumentException [path] 既非 `~` 前缀又非绝对路径
     */
    fun resolveSharedPath(path: String): File {
        return when {
            path == "~" -> sharedRoot
            path.startsWith("~/") -> File(sharedRoot, path.removePrefix("~/"))
            path.startsWith("/") -> File(path)
            else -> throw IllegalArgumentException(
                "host.fs.shared path must start with '~/' or '~' (sdcard relative) " +
                        "or '/' (absolute); got: '$path'"
            )
        }
    }

    // endregion

    companion object {
        /** scripts 根目录名 (相对于 filesDir) */
        private const val SCRIPTS_ROOT = "scripts"

        /** services 根目录名 (相对于 filesDir) */
        private const val SERVICES_ROOT = "services"
        private const val DIR_SRC = "src"
        private const val DIR_DATA = "data"
        private const val DIR_KV = "kv"
        private const val DIR_SQL = "sql"
        private const val DIR_FS = "fs"
        private const val DIR_EXTERNAL = "scripts-external"

        /** manifest 文件名 (脚本/服务通用, 各自目录下)。 */
        private const val FILE_MANIFEST = "manifest.json"
    }
}
