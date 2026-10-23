package cn.listenly.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "listenly")

/** Reader display mode for 精听. */
enum class TranscriptDisplay(val id: String, val label: String) {
    Hidden("hidden", "隐藏"),
    Original("original", "原文"),
    Bilingual("bilingual", "双语"),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Hidden
    }
}

/**
 * Device-local preferences that never sync: what is selected right now, the sync session itself,
 * and migration bookkeeping. Synced settings (accent, dark mode, speed) live in `records`.
 */
class AppPreferences(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val selectedPaper = stringPreferencesKey("selected_paper")
        val focusArticle = stringPreferencesKey("focus_article")
        val display = stringPreferencesKey("display")
        val playbackMode = stringPreferencesKey("playback_mode")
        val legacyMigrated = booleanPreferencesKey("legacy_migrated")
        val syncAccount = stringPreferencesKey("sync_account")
        val syncCursor = longPreferencesKey("sync_cursor")
        val syncOwner = stringPreferencesKey("sync_owner")
        val syncLastAt = longPreferencesKey("sync_last_at")
        val syncProgress = booleanPreferencesKey("sync_progress")
        val syncStats = booleanPreferencesKey("sync_stats")
        val syncSettings = booleanPreferencesKey("sync_settings")
    }

    @Volatile private var cachedDeviceId: String? = null

    /** Local id until the user signs in; then the server-assigned device id. */
    suspend fun deviceId(): String = cachedDeviceId ?: run {
        val existing = store.data.first()[Keys.deviceId]
        val id = existing ?: "local_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        if (existing == null) store.edit { it[Keys.deviceId] = id }
        cachedDeviceId = id
        id
    }

    suspend fun setDeviceId(id: String) {
        store.edit { it[Keys.deviceId] = id }
        cachedDeviceId = id
    }

    fun cachedDeviceIdOrNull(): String? = cachedDeviceId

    val selectedPaper: Flow<String?> = store.data.map { it[Keys.selectedPaper] }
    val focusArticle: Flow<String?> = store.data.map { it[Keys.focusArticle] }
    val display: Flow<TranscriptDisplay> = store.data.map { TranscriptDisplay.fromId(it[Keys.display]) }
    val playbackMode: Flow<String?> = store.data.map { it[Keys.playbackMode] }

    suspend fun select(paperId: String, articleId: String?) = store.edit {
        it[Keys.selectedPaper] = paperId
        if (articleId != null) it[Keys.focusArticle] = articleId else it.remove(Keys.focusArticle)
    }

    suspend fun setDisplay(display: TranscriptDisplay) = store.edit { it[Keys.display] = display.id }
    suspend fun setPlaybackMode(id: String) = store.edit { it[Keys.playbackMode] = id }

    suspend fun legacyMigrated(): Boolean = store.data.first()[Keys.legacyMigrated] == true
    suspend fun setLegacyMigrated() = store.edit { it[Keys.legacyMigrated] = true }

    // ---- sync session ----------------------------------------------------------------------

    val syncAccountJson: Flow<String?> = store.data.map { it[Keys.syncAccount] }
    suspend fun setSyncAccountJson(json: String?) = store.edit {
        if (json == null) it.remove(Keys.syncAccount) else it[Keys.syncAccount] = json
    }

    /** The account whose data this device's records belong to, once any account has signed in. */
    suspend fun syncOwner(): String? = store.data.first()[Keys.syncOwner]
    suspend fun setSyncOwner(userId: String) = store.edit { it[Keys.syncOwner] = userId }

    suspend fun syncCursor(): Long = store.data.first()[Keys.syncCursor] ?: 0L
    suspend fun setSyncCursor(cursor: Long) = store.edit { it[Keys.syncCursor] = cursor }

    val syncLastAt: Flow<Long?> = store.data.map { it[Keys.syncLastAt] }
    suspend fun setSyncLastAt(millis: Long) = store.edit { it[Keys.syncLastAt] = millis }

    val syncToggles: Flow<SyncToggles> = store.data.map {
        SyncToggles(
            progress = it[Keys.syncProgress] ?: true,
            stats = it[Keys.syncStats] ?: true,
            settings = it[Keys.syncSettings] ?: false,
        )
    }

    suspend fun setSyncToggles(toggles: SyncToggles) = store.edit {
        it[Keys.syncProgress] = toggles.progress
        it[Keys.syncStats] = toggles.stats
        it[Keys.syncSettings] = toggles.settings
    }
}

/** Which record collections take part in sync (同步与账号 → 同步内容). */
data class SyncToggles(val progress: Boolean, val stats: Boolean, val settings: Boolean) {
    val collections: List<String>
        get() = buildList {
            if (progress) addAll(listOf(Collections.ANSWER, Collections.POSITION, Collections.COMPLETION))
            if (stats) addAll(listOf(Collections.BOOKMARK, Collections.ACTIVITY))
            if (settings) add(Collections.SETTING)
        }
}
