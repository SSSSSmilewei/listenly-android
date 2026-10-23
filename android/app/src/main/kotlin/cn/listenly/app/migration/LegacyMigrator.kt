package cn.listenly.app.migration

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.room.withTransaction
import cn.listenly.app.data.AppPreferences
import cn.listenly.app.data.Hlc
import cn.listenly.app.data.LibraryRepository
import cn.listenly.app.data.db.ListenlyDatabase
import cn.listenly.app.data.db.RecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.coroutines.resume

data class MigrationReport(val records: Int, val papers: Int, val skipped: Int, val failedFolders: List<String>)

/**
 * One-time carry-over from the 2.x WebView build (same package, so its files are still here):
 *
 * 1. Folders it copied into `files/imports/<uuid>` are validated and registered in place.
 * 2. Its `localStorage['listenly.state.v1']` (answers, bookmarks, positions, activity, theme,
 *    speed) is read through a hidden WebView on the old origin and converted to records.
 *
 * Nothing old is deleted. The migration is only marked done after both steps succeed, so a crash
 * or a WebView failure simply retries on the next launch. Existing 3.0 records are never overwritten.
 */
class LegacyMigrator(
    private val context: Context,
    private val db: ListenlyDatabase,
    private val library: LibraryRepository,
    private val prefs: AppPreferences,
    private val hlc: Hlc,
) {
    suspend fun runIfNeeded(): MigrationReport? {
        if (prefs.legacyMigrated()) return null
        val hadLegacyInstall = File(context.dataDir, "app_webview").exists() || library.importsRoot.exists()
        if (!hadLegacyInstall) {
            prefs.setLegacyMigrated()
            return null
        }
        val adoption = library.adoptLegacyFolders()
        val stateJson = readLegacyLocalStorage() ?: return MigrationReport(0, adoption.papers, 0, adoption.failed)
        if (stateJson.isEmpty()) {
            prefs.setLegacyMigrated()
            return MigrationReport(0, adoption.papers, 0, adoption.failed)
        }
        val deviceId = prefs.deviceId()
        val papers = library.all().associateBy { it.id }
        val migration = LegacyStateMapper.map(stateJson, deviceId, papers::get)
        val written = writeRecords(migration.records, deviceId)
        migration.selectedPaper?.takeIf { it in papers }?.let { prefs.select(it, migration.focusArticle) }
        migration.playbackMode?.let { prefs.setPlaybackMode(it) }
        prefs.setLegacyMigrated()
        return MigrationReport(written, adoption.papers, migration.skipped, adoption.failed)
    }

    private suspend fun writeRecords(records: List<MigratedRecord>, deviceId: String): Int = db.withTransaction {
        val dao = db.records()
        var written = 0
        for (r in records) {
            if (dao.get(r.collection, r.key) != null) continue
            dao.put(RecordEntity(r.collection, r.key, r.value, deleted = false, clock = hlc.next(), device = deviceId, dirty = true))
            written++
        }
        written
    }

    /** Returns the raw JSON string, "" when 2.x never saved state, or null if the WebView failed. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readLegacyLocalStorage(): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                val webView = runCatching { WebView(context.applicationContext) }.getOrNull()
                if (webView == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.blockNetworkLoads = true
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("localStorage.getItem('$LEGACY_KEY')") { result ->
                            webView.destroy()
                            if (continuation.isActive) continuation.resume(decodeJsResult(result))
                        }
                    }
                }
                continuation.invokeOnCancellation { webView.destroy() }
                // Same origin as 2.x (https://listenly.local), so the same localStorage; nothing is fetched.
                webView.loadDataWithBaseURL(LEGACY_ORIGIN_URL, "<!doctype html><title>migrate</title>", "text/html", "utf-8", null)
            }
        }
    }

    companion object {
        const val LEGACY_KEY = "listenly.state.v1"
        const val LEGACY_ORIGIN_URL = "https://listenly.local/assets/index.html"

        /** evaluateJavascript returns a JSON literal: `null` or a quoted string. */
        fun decodeJsResult(result: String?): String = when {
            result == null || result == "null" -> ""
            else -> runCatching { (Json.parseToJsonElement(result) as JsonPrimitive).content }.getOrDefault("")
        }
    }
}
