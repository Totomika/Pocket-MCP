package io.github.totomika.pocketmcp.permission

import io.github.totomika.pocketmcp.permission.PathMatcher.SDCARD_ROOT
import java.io.File

/**
 * 文件系统通配符匹配 + symlink 防护。
 *
 * 见 docs/04-permissions.md "文件系统通配符"。
 *
 * ## 通配符语法
 * - `~` → SD card 根 (默认 `/storage/emulated/0`, 可通过 [sdcardRoot] 覆盖)
 *   只有**前导** `~` 会被展开: `~/Download/` 后接双星号 ✓;
 *   路径中段 `/data/~user/` 中的 `~` 不动。
 *   与 [io.github.totomika.pocketmcp.data.fs.FsPathManager.resolveSharedPath] 语义一致。
 * - 单星号 (`*`) → 目录下的直接子项, 不递归, 不匹配自身
 * - 双星号 (`**`) → 递归所有子目录及内容, 且匹配自身
 *   - `~/dir/` 后接双星号: 匹配 ~/dir 自身及其所有子项
 *   - `~/dir/` 后接单星号: 匹配 ~/dir/foo 等直接子项, 但不匹配 ~/dir
 *
 * ## symlink 防护
 * 对请求路径做 `File.canonicalPath` (解析所有 symlink 到真实路径),
 * 然后检查真实路径是否匹配授权 glob。
 * 若 symlink 目标逃逸出授权范围 → 不匹配。
 *
 * ## read/write 区分
 * 本类只回答"请求路径是否匹配 glob"。read 还是 write 由调用方决定放哪些 glob 进来,
 * 见 [io.github.totomika.pocketmcp.permission.ScriptPermissionChecker]: read 检查时喂
 * read+write glob 的并集 (write 隐含 read), write 检查时只喂 write glob。
 *
 * ## SDCARD_ROOT 与多用户/子系统
 * Android 多用户/子系统场景下 `/storage/emulated/{uid}` 后缀可能不是 `0`。
 * 应通过 `Environment.getExternalStorageDirectory().absolutePath` 传入实际根路径。
 */
object PathMatcher {

    const val SDCARD_ROOT = "/storage/emulated/0"

    /**
     * 检查请求路径是否被声明的 glob 授权。
     *
     * @param declaredGlob   声明的 glob, 如 "~/Download/STARSTAR"
     * @param requestedPath  请求路径, 如 "~/Download/notes/log.txt"
     * @param sdcardRoot     SD 卡根路径 (默认 [SDCARD_ROOT], 多用户设备需传实际值)
     * @return true=路径在授权范围内
     */
    fun isPathGranted(
        declaredGlob: String,
        requestedPath: String,
        sdcardRoot: String = SDCARD_ROOT,
    ): Boolean {
        // 1. 前导 ~ 替换为 SD 卡根路径
        val glob = normalizePath(declaredGlob, sdcardRoot)
        val requested = normalizePath(requestedPath, sdcardRoot)

        // 2. realpath 规范化 (解析 symlink)
        // SECURITY: 必须用 canonicalPath 解析 symlink 到真实路径
        // 注意: 只在文件存在时做 canonicalPath, 避免在测试环境 (Windows JVM)
        // 或路径尚未创建时 canonicalPath 做不期望的路径转换
        val realRequested = try {
            val file = File(requested)
            if (file.exists()) {
                file.canonicalPath.replace("\\", "/")
            } else {
                requested
            }
        } catch (e: Exception) {
            requested
        }

        // 3. glob → regex
        val regex = globToRegex(glob)

        // 4. 匹配
        return regex.matches(realRequested)
    }

    /**
     * 检查请求路径是否被任意一个已授权的 glob 匹配。
     *
     * @param grantedGlobs   已授权的 glob 列表 (调用方已按 read/write 分桶好)
     * @param requestedPath  请求路径
     * @param sdcardRoot     SD 卡根路径 (默认 [SDCARD_ROOT], 多用户设备需传实际值)
     * @return true=至少一个 glob 匹配
     */
    fun isAnyGranted(
        grantedGlobs: List<String>,
        requestedPath: String,
        sdcardRoot: String = SDCARD_ROOT,
    ): Boolean {
        return grantedGlobs.any { glob ->
            isPathGranted(glob, requestedPath, sdcardRoot)
        }
    }

    /**
     * 规范化路径: 仅替换**前导** `~` 为 sdcard 根, 去除多余分隔符。
     *
     * 全文 `~` 替换会误炸中段 `~` (如 `/data/~user/file`), 与
     * [io.github.totomika.pocketmcp.data.fs.FsPathManager.resolveSharedPath] 一致,
     * 只识别开头 `~` (自 身) 或 `~/` (展开后续相对路径)。
     */
    private fun normalizePath(path: String, sdcardRoot: String = SDCARD_ROOT): String {
        val deTilde = when {
            path == "~" -> sdcardRoot
            path.startsWith("~/") -> sdcardRoot + path.substring(1) // 保留 "/"
            else -> path
        }
        return deTilde
            .replace("\\", "/") // Windows 兼容 (测试用)
            .let { if (it.endsWith("/") && it.length > 1) it.dropLast(1) else it }
    }

    /**
     * glob → regex 转换。
     *
     * - 末尾 `**`: 回溯删除前导 `/`, 生成 `(/.*)?` (匹配自身 + 递归子项)
     * - 中间 `**`: `.*` (任意深度)
     * - 单 `*`:   `[^/]*` (单层匹配)
     * - 其他字符按字面量匹配
     *
     * 注意: 先匹配 `**`, 避免单 `*` 提前替换。
     */
    private fun globToRegex(glob: String): Regex {
        val escaped = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**", i) -> {
                    if (i + 2 >= glob.length) {
                        // 末尾 **: 匹配自身 + 递归子项
                        // 回溯删除前面的 /, 因为 ** 本身会处理路径分隔符语义,
                        // 否则 / 会被 literal 消耗掉, 导致子路径无法匹配 (file.txt 不以 / 开头)。
                        if (escaped.isNotEmpty() && escaped.last() == '/') {
                            escaped.setLength(escaped.length - 1)
                        }
                        escaped.append("(/.*)?")
                    } else {
                        escaped.append(".*")
                    }
                    i += 2
                }

                glob[i] == '*' -> {
                    escaped.append("[^/]*")
                    i++
                }

                else -> {
                    // 转义 regex 特殊字符
                    val ch = glob[i]
                    if (ch in ".[]{}()+?^$|") {
                        escaped.append("\\").append(ch)
                    } else {
                        escaped.append(ch)
                    }
                    i++
                }
            }
        }
        return Regex("^${escaped}$")
    }
}
