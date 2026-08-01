package io.github.totomika.pocketmcp.documents

import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import io.github.totomika.pocketmcp.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * DocumentsProvider 实现: 对外暴露应用私有数据目录 (/data/data/<pkg>).
 *
 * - 用户通过系统 SAF picker 或 MiXplorer / Solid Explorer 等支持 SAF 的文件管理器
 *   在侧边栏中能看到名为 "MCPocket" 的入口, 授权后即可浏览/复制/修改/删除其中文件.
 * - 仅在 debug 构建中通过 src/debug 源集打包, release 不含此 Provider 声明,
 *   因此 release 包不会对外暴露内部存储。
 * - 实现机制与 Termux / Clash 等应用的 "外部文件访问" 形态完全一致。
 *
 * Document ID 模型:
 *   - ROOT_DOC_ID = "primary"  (sentinel, 表示 dataDir 根本身)
 *   - 子项 docId = 相对 dataDir 的相对路径, 如 "files", "databases/log.db"
 *
 * 注意: 空字符串 docId 在某些 SAF 实现 (含部分 Files by Google 版本) 中会被识别为
 * 无效行而不调用 queryChildDocuments, 因此必须使用非空 sentinel。
 *
 * 路径安全: 通过 canonicalPath 校验所有操作目标必须在 dataDir 内,
 * 防止软链接或路径穿越 (如 ".." ) 逃逸出沙盒。
 */
class MCPDataFilesProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "MCPDataFilesProvider"

        /** Root id (SAF Root.COLUMN_ROOT_ID), 仅用于 SAF root 标识。 */
        private const val ROOT_ID = "data"

        /** Root 对应的 document id — sentinel, 表示 dataDir 根本身。绝不能为空串。 */
        private const val ROOT_DOC_ID = "primary"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            BaseColumns._ID,
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
        )
    }

    /**
     * 对应 /data/data/<pkg> 或 /data/user/0/<pkg> (取决于多用户上下文)。
     * 通过 Context.dataDir (API 24+) 拿到当前 user 的私有目录, 自动处理多用户场景。
     */
    private val baseDir: File
        get() = context!!.dataDir

    // ------------------------------------------------------------------
    // 基础回调
    // ------------------------------------------------------------------

    override fun onCreate(): Boolean = true

    // ------------------------------------------------------------------
    // Projection 辅助
    // ------------------------------------------------------------------

    private fun resolveRootProjection(projection: Array<String>?): Array<String> =
        projection ?: DEFAULT_ROOT_PROJECTION

    private fun resolveDocumentProjection(projection: Array<String>?): Array<String> =
        projection ?: DEFAULT_DOCUMENT_PROJECTION

    // ------------------------------------------------------------------
    // 根节点
    // ------------------------------------------------------------------

    override fun queryRoots(projection: Array<String>?): android.database.Cursor {
        Log.d(TAG, "queryRoots()")
        val proj = resolveRootProjection(projection)
        val cursor = MatrixCursor(proj)
        val app = context ?: run {
            Log.w(TAG, "queryRoots: context null, returning empty")
            return cursor
        }

        val row = cursor.newRow()
        proj.forEach { col ->
            when (col) {
                Root.COLUMN_ROOT_ID -> row.add(col, ROOT_ID)
                Root.COLUMN_FLAGS -> row.add(
                    col,
                    (Root.FLAG_LOCAL_ONLY
                            or Root.FLAG_SUPPORTS_CREATE
                            or Root.FLAG_SUPPORTS_IS_CHILD)
                )

                Root.COLUMN_ICON -> row.add(col, R.mipmap.ic_launcher)
                Root.COLUMN_TITLE -> row.add(col, app.applicationInfo.loadLabel(app.packageManager))
                Root.COLUMN_SUMMARY -> row.add(col, app.packageName)
                Root.COLUMN_DOCUMENT_ID -> row.add(col, ROOT_DOC_ID)
            }
        }
        return cursor
    }

    // ------------------------------------------------------------------
    // 单个文档
    // ------------------------------------------------------------------

    override fun queryDocument(
        documentId: String,
        projection: Array<String>?
    ): android.database.Cursor {
        Log.d(TAG, "queryDocument($documentId)")
        val proj = resolveDocumentProjection(projection)
        val cursor = MatrixCursor(proj)
        val file = try {
            docIdToFile(documentId)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "queryDocument: not found $documentId", e)
            throw e
        }
        addRow(cursor, proj, file, documentId, 0)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): android.database.Cursor {
        Log.d(TAG, "queryChildDocuments($parentDocumentId)")
        val proj = resolveDocumentProjection(projection)
        val cursor = MatrixCursor(proj)
        val parent = try {
            docIdToFile(parentDocumentId)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "queryChildDocuments: parent not found $parentDocumentId", e)
            throw e
        }
        if (!parent.isDirectory) {
            Log.w(TAG, "queryChildDocuments: not a directory: $parentDocumentId (real=$parent)")
            throw FileNotFoundException("Not a directory: $parentDocumentId")
        }
        val children = parent.listFiles()
        if (children == null) {
            Log.w(TAG, "listFiles() returned null for $parent (likely permission issue)")
            return cursor
        }
        Log.d(TAG, "listFiles at ${parent.absolutePath} -> ${children.size} items")
        children.forEachIndexed { index, child ->
            val childId = fileToDocId(child)
            if (childId == null) {
                Log.w(TAG, "Skip child (escapes sandbox): ${child.absolutePath}")
                return@forEachIndexed
            }
            addRow(cursor, proj, child, childId, index)
        }
        return cursor
    }

    // ------------------------------------------------------------------
    // 文件打开
    // ------------------------------------------------------------------

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.d(TAG, "openDocument($documentId, mode=$mode)")
        val file = docIdToFile(documentId)
        if (!file.exists()) {
            throw FileNotFoundException("File not found: $documentId")
        }
        val modeBits = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, modeBits)
    }

    // ------------------------------------------------------------------
    // 文档 CRUD
    // ------------------------------------------------------------------

    override fun createDocument(
        parentDocumentId: String,
        displayName: String,
        mimeType: String
    ): String {
        Log.d(TAG, "createDocument($parentDocumentId, $displayName, $mimeType)")
        val parent = docIdToFile(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException("Parent not a directory: $parentDocumentId")
        }
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val baseName = sanitizeName(displayName, isDir)
        var candidateName = baseName
        var candidate = File(parent, candidateName)
        var suffix = 1
        while (candidate.exists()) {
            val dot = baseName.lastIndexOf('.')
            candidateName = if (!isDir && dot > 0) {
                "${baseName.substring(0, dot)} ($suffix)${baseName.substring(dot)}"
            } else {
                "$baseName ($suffix)"
            }
            candidate = File(parent, candidateName)
            suffix++
        }
        val ok = if (isDir) candidate.mkdirs() else {
            try {
                candidate.createNewFile()
            } catch (e: IOException) {
                false
            }
        }
        if (!ok && !candidate.exists()) {
            throw IllegalStateException("Failed to create: $candidateName")
        }
        return fileToDocId(candidate)
            ?: throw IllegalStateException("Path escapes sandbox: $candidate")
    }

    override fun deleteDocument(documentId: String) {
        Log.d(TAG, "deleteDocument($documentId)")
        val file = docIdToFile(documentId)
        if (!file.exists()) {
            throw FileNotFoundException("Not found: $documentId")
        }
        val deleted = file.deleteRecursively()
        if (!deleted && file.exists()) {
            throw IllegalStateException("Failed to delete: $documentId")
        }
        notifyChange(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        Log.d(TAG, "renameDocument($documentId, $displayName)")
        val file = docIdToFile(documentId)
        val parent = file.parentFile ?: throw FileNotFoundException("No parent: $documentId")
        val isDir = file.isDirectory
        val targetName = sanitizeName(displayName, isDir)
        val target = File(parent, targetName)
        if (target.exists()) {
            throw IllegalStateException("Target exists: $targetName")
        }
        if (!file.renameTo(target)) {
            throw IllegalStateException("Rename failed: $documentId -> $targetName")
        }
        return fileToDocId(target)
            ?: throw IllegalStateException("Renamed path escapes sandbox: $target")
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        Log.d(TAG, "moveDocument($sourceDocumentId -> parent $targetParentDocumentId)")
        val src = docIdToFile(sourceDocumentId)
        val targetParent = docIdToFile(targetParentDocumentId)
        if (!targetParent.isDirectory) {
            throw FileNotFoundException("Target parent not a directory: $targetParentDocumentId")
        }
        val target = File(targetParent, src.name)
        if (target.exists()) {
            throw IllegalStateException("Target exists: ${target.name}")
        }
        if (!src.renameTo(target)) {
            throw IllegalStateException("Move failed: $sourceDocumentId -> $targetParentDocumentId")
        }
        return fileToDocId(target)
            ?: throw IllegalStateException("Moved path escapes sandbox: $target")
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = docIdToFile(parentDocumentId).canonicalFile
            val child = docIdToFile(documentId).canonicalFile
            val isChild = child.canonicalPath.startsWith(parent.canonicalPath) && parent != child
            Log.d(TAG, "isChildDocument($parentDocumentId, $documentId) -> $isChild")
            isChild
        } catch (e: IOException) {
            false
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /**
     * 将 documentId 转为 File:
     *   - "primary" (sentinel) → dataDir 根
     *   - 其它 → File(dataDir, docId), 做 canonicalPath 校验确保仍在 dataDir 内
     *
     * @throws FileNotFoundException 若路径逃逸出 dataDir
     */
    @Throws(FileNotFoundException::class)
    private fun docIdToFile(docId: String): File {
        val base = baseDir
        val target = if (docId == ROOT_DOC_ID || docId.isEmpty()) base else File(base, docId)
        return try {
            val baseCanon = base.canonicalPath
            val targetCanon = target.canonicalPath
            if (targetCanon != baseCanon && !targetCanon.startsWith("$baseCanon/")) {
                throw FileNotFoundException("Path escapes data dir: $docId")
            }
            File(targetCanon)
        } catch (e: IOException) {
            throw FileNotFoundException("Cannot resolve path: $docId - ${e.message}")
        }
    }

    /**
     * 由 File 反推 documentId:
     *   - 若 file == dataDir → "primary" (sentinel)
     *   - 否则返回相对 dataDir 的相对路径
     *   - 若逃逸出 dataDir → null
     */
    private fun fileToDocId(file: File): String? {
        return try {
            val baseCanon = baseDir.canonicalPath
            val fileCanon = file.canonicalPath
            if (fileCanon == baseCanon) return ROOT_DOC_ID
            if (!fileCanon.startsWith("$baseCanon/")) return null
            fileCanon.removePrefix("$baseCanon/")
        } catch (e: IOException) {
            null
        }
    }

    /** 由文件元信息推出 MIME 类型, 目录为特殊值 [Document.MIME_TYPE_DIR]。 */
    private fun mimeFor(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val name = file.name
        val ext = name.substringAfterLast('.', "")
        val lowerExt = ext.lowercase()
        if (lowerExt.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(lowerExt)
            ?: "application/octet-stream"
    }

    /** 由文件类型决定 [Document.FLAG_*] 位掩码。 */
    private fun flagsFor(file: File): Int {
        var flags = 0
        if (file.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE
        flags = flags or Document.FLAG_SUPPORTS_DELETE
        flags = flags or Document.FLAG_SUPPORTS_RENAME
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        }
        flags = flags or Document.FLAG_SUPPORTS_MOVE
        flags = flags or Document.FLAG_SUPPORTS_REMOVE
        return flags
    }

    /** 向 MatrixCursor 添加一行, 仅填充请求的列。 */
    private fun addRow(
        cursor: MatrixCursor,
        proj: Array<String>,
        file: File,
        docId: String,
        rowIndex: Int
    ) {
        if (!file.exists()) return
        val row = cursor.newRow()
        proj.forEach { col ->
            when (col) {
                BaseColumns._ID -> row.add(col, rowIndex.toLong())
                Document.COLUMN_DOCUMENT_ID -> row.add(col, docId)
                Document.COLUMN_DISPLAY_NAME -> row.add(col, file.name)
                Document.COLUMN_MIME_TYPE -> row.add(col, mimeFor(file))
                Document.COLUMN_SIZE -> row.add(col, if (file.isFile) file.length() else 0L)
                Document.COLUMN_LAST_MODIFIED -> row.add(col, file.lastModified())
                Document.COLUMN_FLAGS -> row.add(col, flagsFor(file))
            }
        }
    }

    /**
     * 清理文件名: 去掉路径分隔符、空字符, 去掉前后空白。
     * 阻止单独 "." 或 ".." 作为名字。
     */
    private fun sanitizeName(raw: String, isDir: Boolean): String {
        var name = raw.trim()
        if (name.isEmpty()) name = if (isDir) "new_folder" else "unnamed"
        name = name.replace('/', '_').replace('\\', '_')
        if (name == "." || name == "..") name = "_$name"
        return name
    }

    /** 通知 ContentResolver 该文档已变更, 让客户端缓存失效。 */
    private fun notifyChange(documentId: String) {
        val ctx = context ?: return
        try {
            val uri = DocumentsContract.buildDocumentUri(
                "${ctx.packageName}.datafiles",
                documentId
            )
            ctx.contentResolver.notifyChange(uri, null)
        } catch (e: Exception) {
            Log.w(TAG, "notifyChange failed for $documentId", e)
        }
    }
}