package cn.listenly.app.data

import android.content.Context
import cn.listenly.app.data.db.ListenlyDatabase
import cn.listenly.app.data.db.PaperEntity
import cn.listenly.app.importer.AssetImportSource
import cn.listenly.app.importer.FileImportSource
import cn.listenly.app.importer.ImportException
import cn.listenly.app.importer.PaperParser
import cn.listenly.app.model.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** Papers on this device: bundled demo, imported folders and folders carried over from 2.x. */
class LibraryRepository(
    private val context: Context,
    private val db: ListenlyDatabase,
    private val audioProbe: (String) -> Double?,
) {
    private val dao = db.papers()
    private val json get() = StudyRepository.json

    /** Same folder 2.x used, so its copied material is reused in place after an update. */
    val importsRoot: File = File(context.filesDir, "imports")

    val papers: Flow<List<Paper>> = dao.observeAll().map { rows -> rows.mapNotNull(::decode) }

    suspend fun paper(id: String): Paper? = dao.byId(id)?.let(::decode)

    suspend fun all(): List<Paper> = dao.all().mapNotNull(::decode)

    /** Seeds the original CC0 demo the first time (or after it was removed by a bad state). */
    suspend fun ensureDemo() = withContext(Dispatchers.IO) {
        if (dao.byId(DEMO_ID) != null) return@withContext
        val source = AssetImportSource(context, "demo")
        val papers = PaperParser(source).parse()
        dao.upsertAll(papers.map { it.toEntity(origin = "demo", importId = null) })
    }

    /**
     * Validates [folder] (already copied under [importsRoot]) and stores its papers atomically.
     * Re-importing an existing paper id replaces its timeline; answers stay because they key on ids.
     */
    suspend fun importFolder(folder: File, origin: String = "import"): List<Paper> = withContext(Dispatchers.IO) {
        val papers = PaperParser(FileImportSource(folder, audioProbe)).parse()
        val replaced = papers.mapNotNull { dao.byId(it.id) }
        dao.upsertAll(papers.map { it.toEntity(origin, folder.name) })
        // A re-import supersedes the previous copy. Remove app-made import folders nothing points at
        // any more; folders inherited from 2.x are left alone.
        for (old in replaced) {
            val oldId = old.importId ?: continue
            if (old.origin == "import" && oldId != folder.name && dao.countForImport(oldId) == 0) {
                File(importsRoot, oldId).takeIf { it.canonicalFile.parentFile == importsRoot.canonicalFile }?.deleteRecursively()
            }
        }
        papers
    }

    /** Folders 2.x copied into files/imports that are not in the database yet. Bad ones are reported, never deleted. */
    suspend fun adoptLegacyFolders(): LegacyAdoption = withContext(Dispatchers.IO) {
        val folders = importsRoot.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.lastModified() }
        var adopted = 0
        val failed = mutableListOf<String>()
        for (folder in folders) {
            if (dao.countForImport(folder.name) > 0) continue
            try {
                adopted += importFolder(folder, origin = "legacy").size
            } catch (error: ImportException) {
                failed += "${folder.name}：${error.issues.firstOrNull() ?: "无法识别"}"
            }
        }
        LegacyAdoption(adopted, failed)
    }

    private fun decode(row: PaperEntity): Paper? = runCatching { json.decodeFromString(Paper.serializer(), row.json) }.getOrNull()

    private fun Paper.toEntity(origin: String, importId: String?) = PaperEntity(
        id = id,
        title = title,
        collectionId = collection.id,
        collectionTitle = collection.title,
        year = year,
        json = json.encodeToString(Paper.serializer(), this),
        origin = origin,
        importId = importId,
        importedAt = System.currentTimeMillis(),
    )

    companion object {
        const val DEMO_ID = "listenly-original"
    }
}

data class LegacyAdoption(val papers: Int, val failed: List<String>)
