package cn.listenly.app.importer

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File

/** A folder that already lives in app-private storage. */
class FileImportSource(
    private val root: File,
    private val probe: (String) -> Double?,
) : ImportSource {
    private val canonicalRoot = root.canonicalFile

    override fun files(): List<String> = canonicalRoot.walkTopDown()
        .maxDepth(MAX_DEPTH)
        .filter { it.isFile }
        .map { it.relativeTo(canonicalRoot).invariantSeparatorsPath }
        .toList()

    override fun readText(path: String): String {
        val file = inside(path)
        require(file.length() <= MAX_TEXT_BYTES) { "文本超过 8 MiB" }
        return file.readText(Charsets.UTF_8)
    }

    override fun audioDuration(path: String): Double? = probe(inside(path).path)

    override fun audioLocation(path: String): String = inside(path).path

    private fun inside(path: String): File {
        val file = File(canonicalRoot, path).canonicalFile
        require(file.path.startsWith(canonicalRoot.path + File.separator)) { "路径超出导入目录" }
        return file
    }

    companion object {
        const val MAX_DEPTH = 32
        const val MAX_TEXT_BYTES = 8L * 1024 * 1024
    }
}

/** Bundled material under `assets/<dir>`. */
class AssetImportSource(private val context: Context, private val dir: String) : ImportSource {
    override fun files(): List<String> = list(dir).map { it.removePrefix("$dir/") }

    private fun list(path: String): List<String> {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) return listOf(path)
        return children.flatMap { list("$path/$it") }
    }

    override fun readText(path: String): String =
        context.assets.open("$dir/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun audioDuration(path: String): Double? = runCatching {
        context.assets.openFd("$dir/$path").use { fd ->
            MediaMetadataRetriever().useRetriever { retriever ->
                retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                retriever.durationSeconds()
            }
        }
    }.getOrNull()

    override fun audioLocation(path: String): String = "asset:///$dir/$path"
}

/** Reads the real decoded duration; never trusts the duration written in paper.json. */
fun probeAudioDuration(path: String): Double? = runCatching {
    MediaMetadataRetriever().useRetriever { retriever ->
        retriever.setDataSource(path)
        retriever.durationSeconds()
    }
}.getOrNull()

private inline fun <T> MediaMetadataRetriever.useRetriever(block: (MediaMetadataRetriever) -> T): T =
    try {
        block(this)
    } finally {
        release()
    }

private fun MediaMetadataRetriever.durationSeconds(): Double? =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.div(1000.0)
