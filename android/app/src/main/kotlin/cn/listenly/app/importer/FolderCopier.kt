package cn.listenly.app.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.UUID

data class CopyProgress(val files: Int, val bytes: Long)

/**
 * Copies a folder picked with ACTION_OPEN_DOCUMENT_TREE into app-private storage, with the same
 * limits as 2.x: ≤ 25,000 files, ≤ 512 MB per file, ≤ 2 GB per import, ≤ 32 levels, no path escape.
 * On any failure the partial copy is removed.
 */
class FolderCopier(private val resolver: ContentResolver, private val importsRoot: File) {

    suspend fun copy(tree: Uri, onProgress: (CopyProgress) -> Unit = {}): File {
        importsRoot.mkdirs()
        val destination = File(importsRoot, UUID.randomUUID().toString())
        if (!destination.mkdir()) throw IOException("无法创建导入目录")
        try {
            val state = State()
            copyChildren(tree, DocumentsContract.getTreeDocumentId(tree), destination.canonicalFile, "", 0, state, onProgress)
            if (state.files == 0) throw IOException("所选文件夹没有可读取的文件")
            return destination
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private class State {
        var files = 0
        var bytes = 0L
    }

    private suspend fun copyChildren(
        tree: Uri,
        parentId: String,
        root: File,
        relative: String,
        depth: Int,
        state: State,
        onProgress: (CopyProgress) -> Unit,
    ) {
        if (depth > MAX_DEPTH) throw IOException("文件夹层级过深")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val entries = resolver.query(children, columns, null, null, null)?.use { cursor ->
            val list = mutableListOf<Entry>()
            while (cursor.moveToNext()) {
                val size: Long? = if (cursor.isNull(3)) null else cursor.getLong(3)
                list += Entry(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(), cursor.getString(2), size)
            }
            list
        } ?: throw IOException("无法读取文件夹")

        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (!isSafeSegment(entry.name)) throw IOException("无效的文件名：${entry.name}")
            val path = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
            val target = inside(root, path)
            if (entry.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (!target.exists() && !target.mkdirs()) throw IOException("无法创建子文件夹")
                copyChildren(tree, entry.id, root, path, depth + 1, state, onProgress)
                continue
            }
            if (++state.files > MAX_FILES) throw IOException("单次导入文件超过 25000 个，请分批导入")
            if ((entry.size ?: 0) > MAX_FILE_BYTES) throw IOException("单个文件超过 512 MB：${entry.name}")
            target.parentFile?.mkdirs()
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
            var fileBytes = 0L
            (resolver.openInputStream(document) ?: throw IOException("无法读取 ${entry.name}")).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        fileBytes += count
                        state.bytes += count
                        if (fileBytes > MAX_FILE_BYTES || state.bytes > MAX_IMPORT_BYTES) {
                            throw IOException("单次导入超过 2 GB，或单个文件超过 512 MB")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (state.files % 25 == 0) onProgress(CopyProgress(state.files, state.bytes))
        }
    }

    private data class Entry(val id: String, val name: String, val mime: String?, val size: Long?)

    companion object {
        const val MAX_FILES = 25_000
        const val MAX_DEPTH = 32
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024

        fun isSafeSegment(name: String?): Boolean =
            !name.isNullOrEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name

        fun inside(root: File, path: String): File {
            val file = File(root, path).canonicalFile
            if (!file.path.startsWith(root.path + File.separator)) throw IOException("路径超出导入目录")
            return file
        }
    }
}
