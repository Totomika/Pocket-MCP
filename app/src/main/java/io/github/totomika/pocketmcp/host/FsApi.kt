package io.github.totomika.pocketmcp.host

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import io.github.totomika.pocketmcp.data.fs.FsPathManager
import io.github.totomika.pocketmcp.runtime.RuntimeFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * host.fs API 实现 (private + external + shared)，共用 [injectFsOperations]。
 *
 * ## private (自动授予, 沙箱隔离)
 * 脚本私有目录。
 * SECURITY: 解析后路径必须落在 [FsPathManager.privateRoot] 的 canonical 子树内,
 * 否则抛 [SecurityException] —— 防止 `../` 越权访问其它 namespace 或 app 私有布局。
 *
 * ## external (自动授予, 沙箱隔离)
 * Android/data/app，可被文件管理器访问。
 * SECURITY: 同样强制沙箱检查。
 *
 * ## shared (权限检查, symlink 防护)
 * 共享文件系统，`~` 展开为设备外部存储根目录。
 * SECURITY:
 *   1. **必须**提供 [FsPermissionChecker]，否则在 inject 阶段抛 [IllegalStateException]
 *      —— 配置错误应 fail-fast, 而非静默 (无 checker 时 shared 命名空间不该隐式可用)。
 *   2. 调用 checker 前, 在 FsApi 边界对路径做 canonical 化 (解析中间目录上的 symlink)
 *      再交给 checker; PathMatcher 内部再做一次深度防御, 双层保护 symlink escape。
 *
 * 三个命名空间暴露的 JS 方法名一致:
 * `read` / `readBytes` / `write` / `append` / `exists` /
 * `mkdir` / `readdir` / `stat` / `delete` / `rename` / `lines`。
 *
 * `lines(path)` 返回 async generator, 流式逐行遍历大文件 (有状态迭代器, O(n))。
 *
 * 见 docs/03-host-api.md 第 2 层。
 */
class FsApi(
    private val pathManager: FsPathManager,
    private val permissionChecker: FsPermissionChecker? = null,
) : HostApi {

    /**
     * 行迭代器注册表。
     * key = "$namespace:$prefix:$uuid", value = BufferedReader (保持文件句柄打开)。
     * 在 [cleanup] 时关闭所有该 namespace 下的未关闭迭代器。
     */
    private val iterators = ConcurrentHashMap<String, BufferedReader>()

    override fun inject(quickJs: QuickJs, namespace: String, scope: CoroutineScope) {
        // scope 暂未使用: asyncFunction 内部由 quickjs-kt 管理协程调度,
        // withContext(Dispatchers.IO) 使用全局 IO 调度器。保留参数以符合 HostApi 接口约定。
        injectPrivate(quickJs, namespace)
        injectExternal(quickJs, namespace)
        injectShared(quickJs, namespace)
    }

    override fun cleanup(namespace: String) {
        // 关闭该 namespace 下所有未关闭的行迭代器, 防文件句柄泄漏
        val nsPrefix = "$namespace:"
        iterators.keys.filter { it.startsWith(nsPrefix) }.forEach { id ->
            iterators.remove(id)?.close()
        }
    }

    // region namespace 注入

    private fun injectPrivate(quickJs: QuickJs, namespace: String) {
        injectFsOperations(
            quickJs = quickJs,
            namespace = namespace,
            prefix = "private",
            strategy = SandboxPathStrategy(pathManager.privateRoot(namespace)),
            permissionChecker = null, // private 自动授予, 无 checker
        )
    }

    private fun injectExternal(quickJs: QuickJs, namespace: String) {
        injectFsOperations(
            quickJs = quickJs,
            namespace = namespace,
            prefix = "external",
            strategy = SandboxPathStrategy(pathManager.externalRoot(namespace)),
            permissionChecker = null, // external 自动授予, 无 checker
        )
    }

    private fun injectShared(quickJs: QuickJs, namespace: String) {
        val checker = permissionChecker
            ?: throw IllegalStateException(
                "FsApi: shared namespace requires a non-null FsPermissionChecker; " +
                        "got null. Inject a checker (prod) or an AlwaysGranted stub (tests)."
            )
        injectFsOperations(
            quickJs = quickJs,
            namespace = namespace,
            prefix = "shared",
            strategy = SharedPathStrategy(pathManager),
            permissionChecker = checker,
        )
    }

    // endregion

    // region path strategies

    /**
     * 路径解析策略: 把脚本传入的相对/绝对路径转成最终的 [File]。
     *
     * - [SandboxPathStrategy] 对 private/external 做沙箱 confinement。
     * - [SharedPathStrategy] 对 shared 做 `~` 展开。
     */
    private sealed interface PathStrategy {
        fun resolve(rawPath: String): File
    }

    /**
     * 沙箱策略: 将相对路径解析到 [root] 下, 并强校验最终 canonical 路径
     * 必须在 [root] 的 canonical 子树内。
     *
     * 拒绝 `../` 穿越以及 symlink 逃逸 (canonicalPath 解析 symlink)。
     */
    private class SandboxPathStrategy(private val root: File) : PathStrategy {
        override fun resolve(rawPath: String): File {
            // root 已是 mkdirs() 后的实际目录, canonicalPath 代价低
            val rootCanon = root.canonicalPath
            val resolvedCanon = File(root, rawPath).canonicalPath
            if (resolvedCanon != rootCanon &&
                !resolvedCanon.startsWith(rootCanon + File.separator)
            ) {
                throw SecurityException(
                    "Path escapes fs sandbox: '$rawPath' resolves to '$resolvedCanon', " +
                            "expected under '$rootCanon'."
                )
            }
            return File(resolvedCanon)
        }
    }

    /**
     * shared 策略: 委托 [FsPathManager.resolveSharedPath] 做 `~` 展开。
     * canonical 化 (symlink 防护) 在 checker 调用处做 (见 [canonicalForCheck])。
     */
    private class SharedPathStrategy(
        private val pathManager: FsPathManager,
    ) : PathStrategy {
        override fun resolve(rawPath: String): File = pathManager.resolveSharedPath(rawPath)
    }

    // endregion

// region api 注入

    /**
     * 注入指定命名空间的文件操作。
     *
     * @param prefix JS 内部函数名前缀 (`private` / `external` / `shared`)
     * @param strategy 路径解析策略
     * @param permissionChecker 非 null 时在读写操作前执行权限检查 (仅 shared 使用)
     */
    private fun injectFsOperations(
        quickJs: QuickJs,
        namespace: String,
        prefix: String,
        strategy: PathStrategy,
        permissionChecker: FsPermissionChecker? = null,
    ) {
        val fnPrefix = "__fs_$prefix"

        val resolvePath: (String) -> File = { rawPath -> strategy.resolve(rawPath) }

        val checkRead: (File) -> Unit = { file ->
            permissionChecker?.checkRead(namespace, canonicalForCheck(file))
        }
        val checkWrite: (File) -> Unit = { file ->
            permissionChecker?.checkWrite(namespace, canonicalForCheck(file))
        }

        // read → 文本读取, 支持 (path, force?, offset?, length?) 范围读取:
        //   - 全量: read(path) / read(path, force)
        //   - 范围: read(path, force, offset, length) — 从第 offset 字节起读 length 字节
        // 两层防护防 OOM 损坏引擎:
        //   1. 固定 8MiB 合理默认限制 (force=true 可跳过), 基于实际读取字节数 (非文件大小)
        //   2. 实际 QuickJS 内存检查 (始终生效, force=true 也不跳过)
        //      memoryLimit=UNLIMITED (无限制) 时跳过此检查
        // 范围读取用 RandomAccessFile.seek 定位 (minSdk=26 无 InputStream.readNBytes),
        // UTF-8 解码对半截多字节字符用 U+FFFD 替换 (String(bytes, UTF_8) 默认行为).
        quickJs.asyncFunction<String>("${fnPrefix}_read") { args ->
            val path = requirePathArg(args)
            val force = args.getOrNull(1) as? Boolean ?: false
            val offset = (args.getOrNull(2) as? Number)?.toLong() ?: 0L
            val length = (args.getOrNull(3) as? Number)?.toLong() // null = 读到 EOF

            if (offset < 0) {
                throw IllegalArgumentException("read: offset must be >= 0 (got $offset)")
            }
            if (length != null && length <= 0) {
                throw IllegalArgumentException("read: length must be > 0 (got $length)")
            }

            val file = resolvePath(path)
            checkRead(file)

            // 在 withContext(Dispatchers.IO) 前读取 QuickJS 内存信息
            // (asyncFunction lambda 初始运行在 QuickJs dispatcher 上, 访问 memoryUsage 更安全)
            val memLimit = quickJs.memoryLimit
            val memUsed = if (!RuntimeFactory.isUnlimited(memLimit)) quickJs.memoryUsage.memoryUsedSize else 0L

            withContext(Dispatchers.IO) {
                val fileSize = file.length()
                val rangeRead = offset > 0L || length != null

                // 计算本次实际读取字节数 (用于限额检查); offset 越界时为 0
                val effectiveLength = if (offset >= fileSize) {
                    0L
                } else {
                    val remaining = fileSize - offset
                    if (length != null) minOf(length, remaining) else remaining
                }

                // 层 1: 固定安全限制 (可被 force=true 跳过)
                if (!force && effectiveLength > READ_TEXT_MAX_BYTES) {
                    throw IllegalStateException(
                        "read: read size $effectiveLength bytes exceeds limit $READ_TEXT_MAX_BYTES " +
                                "(path=$path, offset=$offset" +
                                (length?.let { ", length=$it" } ?: "") +
                                "). Use force=true to bypass, or host.fs.$prefix.lines(path) for paged reading."
                    )
                }

                // 层 2: 实际 QuickJS 堆内存检查 (始终生效, 防止 OOM 损坏引擎)
                if (!RuntimeFactory.isUnlimited(memLimit) &&
                    memUsed + effectiveLength + READ_MEM_SAFETY_MARGIN > memLimit
                ) {
                    val available = memLimit - memUsed
                    throw IllegalStateException(
                        "read: read $effectiveLength bytes would exceed available memory " +
                                "(used $memUsed + read $effectiveLength > limit $memLimit, " +
                                "available $available bytes, path=$path). " +
                                "Increase memoryLimit in advanced settings, or use host.fs.$prefix.lines(path) for paged reading."
                    )
                }

                when {
                    !rangeRead -> file.readText()
                    effectiveLength == 0L -> ""
                    else -> {
                        if (effectiveLength > Int.MAX_VALUE.toLong()) {
                            throw IllegalStateException(
                                "read: range length $effectiveLength exceeds Int.MAX_VALUE (${Int.MAX_VALUE}), " +
                                        "use host.fs.$prefix.lines(path) for large files."
                            )
                        }
                        // 范围读取: RandomAccessFile 定位 + readFully; UTF-8 解码对半截字符用 U+FFFD 替换
                        java.io.RandomAccessFile(file, "r").use { raf ->
                            raf.seek(offset)
                            val bytes = ByteArray(effectiveLength.toInt())
                            raf.readFully(bytes)
                            String(bytes, Charsets.UTF_8)
                        }
                    }
                }
            }
        }

        // readBytes → 返回 Base64 编码字符串 (JS 侧 host.crypto.b64decode 解码为 Uint8Array)
        // 防 OOM: 超过 [READ_BYTES_MAX_BYTES] 抛, 避免进程崩溃
        quickJs.asyncFunction<String>("${fnPrefix}_readBytes") { args ->
            val path = requirePathArg(args)
            val file = resolvePath(path)
            checkRead(file)
            withContext(Dispatchers.IO) {
                val size = file.length()
                if (size > READ_BYTES_MAX_BYTES) {
                    throw IllegalStateException(
                        "readBytes: file size $size bytes exceeds limit $READ_BYTES_MAX_BYTES " +
                                "(path=$path)"
                    )
                }
                java.util.Base64.getEncoder().encodeToString(file.readBytes())
            }
        }

        // write
        quickJs.asyncFunction<Unit>("${fnPrefix}_write") { args ->
            val path = requirePathArg(args)
            val content = args.getOrNull(1)?.toString() ?: ""
            val file = resolvePath(path)
            checkWrite(file)
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        }

        // append
        quickJs.asyncFunction<Unit>("${fnPrefix}_append") { args ->
            val path = requirePathArg(args)
            val content = args.getOrNull(1)?.toString() ?: ""
            val file = resolvePath(path)
            checkWrite(file)
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.appendText(content)
            }
        }

        // exists (统一 IO 调度, 与其它 op 一致)
        quickJs.asyncFunction<Boolean>("${fnPrefix}_exists") { args ->
            val path = requirePathArg(args)
            val file = resolvePath(path)
            checkRead(file)
            withContext(Dispatchers.IO) { file.exists() }
        }

        // mkdir → 返回是否成功创建 (Boolean)
        quickJs.asyncFunction<Boolean>("${fnPrefix}_mkdir") { args ->
            val path = requirePathArg(args)
            val file = resolvePath(path)
            checkWrite(file)
            withContext(Dispatchers.IO) { file.mkdirs() }
        }

        // readdir → JSON 字符串数组; 防 OOM: 限制 [READDIR_MAX_ENTRIES]
        quickJs.asyncFunction<String>("${fnPrefix}_readdir") { args ->
            val path = requirePathArg(args)
            val file = resolvePath(path)
            checkRead(file)
            withContext(Dispatchers.IO) {
                val names = file.list()
                if (names == null) {
                    "[]"
                } else {
                    if (names.size > READDIR_MAX_ENTRIES) {
                        throw IllegalStateException(
                            "readdir: directory contains ${names.size} entries, " +
                                    "exceeds limit $READDIR_MAX_ENTRIES (path=$path)"
                        )
                    }
                    JSONArray(names.toList()).toString()
                }
            }
        }

        // stat → JSON
        quickJs.asyncFunction<String>("${fnPrefix}_stat") { args ->
            val path = requirePathArg(args)
            val file = resolvePath(path)
            checkRead(file)
            withContext(Dispatchers.IO) {
                if (!file.exists()) {
                    // 不存在抛 IllegalStateException (非参数错误), 与 IllegalArgumentException 区分
                    throw IllegalStateException("File not found: $path")
                }
                JSONObject()
                    .put("size", file.length())
                    .put("isFile", file.isFile)
                    .put("isDir", file.isDirectory)
                    .put("mtime", file.lastModified())
                    .toString()
            }
        }

        // delete → 返回是否成功 (Boolean)
        quickJs.asyncFunction<Boolean>("${fnPrefix}_delete") { args ->
            val path = requirePathArg(args)
            val file = resolvePath(path)
            checkWrite(file)
            withContext(Dispatchers.IO) { file.deleteRecursively() }
        }

        // rename
        quickJs.asyncFunction<Unit>("${fnPrefix}_rename") { args ->
            val oldPath = requirePathArg(args)
            val newPath = args.getOrNull(1)?.toString()
                ?: throw IllegalArgumentException("rename: newPath argument is required")
            val oldFile = resolvePath(oldPath)
            val newFile = resolvePath(newPath)
            checkRead(oldFile) // rename 涉及文件内容位置变更, 需读权限
            checkWrite(oldFile)
            checkWrite(newFile)
            withContext(Dispatchers.IO) {
                newFile.parentFile?.mkdirs()
                if (!oldFile.renameTo(newFile)) {
                    throw IllegalStateException("Failed to rename '$oldPath' to '$newPath'")
                }
            }
        }

        // --- 行迭代器 (有状态, 流式读取大文件, O(n) 总计) ---

        // openLineIter → 返回 iterator ID; Kotlin 侧持有 BufferedReader 保持文件句柄
        quickJs.asyncFunction<String>("${fnPrefix}_openLineIter") { args ->
            val path = requirePathArg(args)
            val file = resolvePath(path)
            checkRead(file)
            withContext(Dispatchers.IO) {
                val reader = file.bufferedReader()
                val id = "$namespace:$prefix:${java.util.UUID.randomUUID()}"
                iterators[id] = reader
                id
            }
        }

        // nextLines → 批量读取下一组行; 返回 JSON { lines: [...], eof: bool }
        // 批量减少 bridge 往返开销 (如 50000 行 / 100 批 = 500 次调用)
        quickJs.asyncFunction<String>("${fnPrefix}_nextLines") { args ->
            val id = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("nextLines: iterator id is required")
            if (!id.startsWith("$namespace:$prefix:")) {
                throw SecurityException("nextLines: iterator id does not belong to this namespace")
            }
            val count = (args.getOrNull(1) as? Number)?.toInt() ?: 100
            if (count <= 0) throw IllegalArgumentException("nextLines: count must be positive")
            val reader = iterators[id]
                ?: throw IllegalStateException("nextLines: iterator not found or already closed")
            withContext(Dispatchers.IO) {
                val lines = mutableListOf<String>()
                repeat(count) {
                    val line = reader.readLine() ?: return@repeat
                    lines.add(line)
                }
                """{"lines":${JSONArray(lines)},"eof":${lines.size < count}}"""
            }
        }

        // closeLineIter → 关闭迭代器, 释放文件句柄
        quickJs.asyncFunction<Unit>("${fnPrefix}_closeLineIter") { args ->
            val id = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("closeLineIter: iterator id is required")
            iterators.remove(id)?.close()
        }

        // JS 侧 wrapper 暴露为 host.fs.<prefix>.<method>
        // quickjs.evaluate 是 suspend, 用 runBlocking 桥接 (与 FetchApi/SystemApi 一致)
        kotlinx.coroutines.runBlocking {
            quickJs.evaluate<Any?>(
                """
                if (typeof host === 'undefined') { var host = {}; }
                host.fs = host.fs || {};
                host.fs.$prefix = {
                  read: (path, force, offset, length) => ${fnPrefix}_read(path, force, offset, length),
                  readBytes: (path) => host.crypto.b64decode(${fnPrefix}_readBytes(path)),
                  write: (path, content) => ${fnPrefix}_write(path, content),
                  append: (path, content) => ${fnPrefix}_append(path, content),
                  exists: (path) => ${fnPrefix}_exists(path),
                  mkdir: (path) => ${fnPrefix}_mkdir(path),
                  readdir: (path) => ${fnPrefix}_readdir(path),
                  stat: (path) => ${fnPrefix}_stat(path),
                  delete: (path) => ${fnPrefix}_delete(path),
                  rename: (oldPath, newPath) => ${fnPrefix}_rename(oldPath, newPath),
                  lines: async function*(path) {
                    const id = await ${fnPrefix}_openLineIter(path);
                    try {
                      while (true) {
                        const batch = JSON.parse(await ${fnPrefix}_nextLines(id, 100));
                        for (const line of batch.lines) {
                          yield line;
                        }
                        if (batch.eof) break;
                      }
                    } finally {
                      await ${fnPrefix}_closeLineIter(id);
                    }
                  },
                };
                """.trimIndent()
            )
        }
    }

    // endregion

    // region helpers

    /**
     * 取第 0 个参数并要求非空字符串; 否则抛清晰错误而非静默用空字符串。
     */
    private fun requirePathArg(args: Array<Any?>): String {
        val raw = args.getOrNull(0)?.toString()
        if (raw.isNullOrEmpty()) {
            throw IllegalArgumentException("path argument is required and must be non-empty")
        }
        return raw
    }

    /**
     * 对 [file] 做 canonical 化用于权限检查。
     *
     * SECURITY — symlink 防护边界:
     * - 文件存在 → [File.canonicalPath] 解析整段路径上的 symlink。
     * - 文件不存在但其父目录存在 → canonical 化父目录后拼接文件名,
     *   仍能解析父级 symlink (覆盖 mkdir/write/append/rename target 等场景)。
     * - 父目录也不存在 → 退化为 [File.absolutePath], 此时不解析 symlink
     *   (后续 PathMatcher 内部仍会再做一次防御性 canonical, 双层保护)。
     *
     * 异常路径 (canonicalFile 抛 IOException 等) 一律退回 [File.absolutePath],
     * 不阻断正常流程, 而是交给 PathMatcher 决定是否拒绝。
     */
    private fun canonicalForCheck(file: File): String {
        return try {
            when {
                file.exists() -> file.canonicalPath
                file.parentFile?.exists() == true ->
                    File(file.parentFile!!.canonicalFile, file.name).absolutePath

                else -> file.absolutePath
            }
        } catch (e: Exception) {
            file.absolutePath
        }
    }

    private companion object {
        /** readBytes 单文件大小上限 (字节), 防 OOM. 16MiB. */
        private const val READ_BYTES_MAX_BYTES = 16L * 1024 * 1024

        /** readdir 单目录条目上限, 防 OOM. */
        private const val READDIR_MAX_ENTRIES = 10_000

        /** read 全量文本读取上限 (字节), 防 OOM. 8MiB.
         *  QuickJS memoryLimit=16MiB, 字符串在 JS 中可能因 split/JSON.stringify 翻倍,
         *  留余量后设 8MiB。超过此大小请用 host.fs.<prefix>.lines(path) 流式分页读取。 */
        private const val READ_TEXT_MAX_BYTES = 8L * 1024 * 1024

        /** read 内存检查安全余量 (字节). JSString 结构开销 + 对齐, 保守估计。 */
        private const val READ_MEM_SAFETY_MARGIN = 64L
    }

    // endregion
}

/**
 * 文件系统权限检查接口。
 *
 * shared 操作前调用, 检查路径是否在授权 glob 范围内。
 * 入参 path 应为 FsApi 边界 canonical 化后的绝对路径。
 */
interface FsPermissionChecker {
    /**
     * 检查读权限。无权限时抛 SecurityException。
     */
    fun checkRead(namespace: String, path: String)

    /**
     * 检查写权限。无权限时抛 SecurityException。
     */
    fun checkWrite(namespace: String, path: String)
}
