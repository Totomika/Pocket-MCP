package io.github.totomika.pocketmcp.script

import io.github.totomika.pocketmcp.data.fs.FsPathManager
import java.io.File

/**
 * 脚本代码文件 + 数据目录存储 (FS 部分)。
 *
 * 重构后元数据与权限已迁至 [ScriptManifestStore]; 本类只负责文件系统层:
 * - 代码: `files/scripts/<ns>/src/script.js`
 * - 数据: `files/scripts/<ns>/data/` (sql/kv/fs)
 *
 * 见 docs/08-distribution.md "导入渠道"。
 *
 * @param pathManager 文件系统路径管理器
 */
class ScriptRepository(
    private val pathManager: FsPathManager,
) {

    /**
     * 脚本代码文件路径。
     *
     * files/scripts/<namespace>/src/script.js
     */
    fun scriptFile(namespace: String): File {
        return File(pathManager.scriptSrcDir(namespace), "script.js")
    }

    /**
     * 脚本数据目录路径。
     *
     * files/scripts/<namespace>/data/
     */
    fun scriptDataDir(namespace: String): File {
        return pathManager.scriptDataDir(namespace)
    }

    /**
     * 存储脚本代码到文件系统。
     */
    fun storeScriptCode(namespace: String, code: String) {
        val file = scriptFile(namespace)
        file.parentFile?.mkdirs()
        file.writeText(code)
    }

    /**
     * 读取脚本代码。
     */
    fun readScriptCode(namespace: String): String? {
        val file = scriptFile(namespace)
        return if (file.exists()) file.readText() else null
    }

    /**
     * 删除脚本代码文件 (不删除数据目录)。
     * 确保 data/ 目录不受影响, 便于下次安装时恢复数据。
     */
    fun deleteScriptCode(namespace: String) {
        val file = scriptFile(namespace)
        if (file.delete()) {
            // 清理空 src 目录 (仅当代码文件删除成功时尝试)
            file.parentFile?.delete()
        }
    }

    /**
     * 删除脚本数据目录 (data/), 保留 src/ 代码文件。
     * 用于卸载时"同时删除数据"的场景。
     */
    fun deleteScriptData(namespace: String) {
        scriptDataDir(namespace).deleteRecursively()
    }

    /**
     * 清理空的脚本根目录 (files/scripts/<namespace>/).
     *
     * 卸载流程最后调用: 当 manifest / src / data 全部删除后,
     * 若目录已空则一并删除, 防止残留空目录。
     */
    fun deleteScriptDirIfEmpty(namespace: String) {
        val dir = pathManager.scriptDir(namespace)
        if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) {
            dir.delete()
        }
    }
}
