package cn.listenly.app

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cn.listenly.app.data.ActivityValue
import cn.listenly.app.data.AppPreferences
import cn.listenly.app.data.Hlc
import cn.listenly.app.data.LibraryRepository
import cn.listenly.app.data.StudyRepository
import cn.listenly.app.data.db.ListenlyDatabase
import cn.listenly.app.importer.FolderCopier
import cn.listenly.app.importer.probeAudioDuration
import cn.listenly.app.migration.LegacyMigrator
import cn.listenly.app.migration.MigrationReport
import cn.listenly.app.playback.PlayerController
import cn.listenly.app.sync.SyncEngine
import cn.listenly.app.sync.SyncFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual dependency wiring; one instance per process. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: ListenlyDatabase = Room.databaseBuilder(context, ListenlyDatabase::class.java, "listenly.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    val prefs = AppPreferences(context)

    private val syncReady = CompletableDeferred<SyncEngine>()
    @Volatile private var syncEngine: SyncEngine? = null
    suspend fun sync(): SyncEngine = syncReady.await()

    /** Seeded lazily from the highest stored clock so it never runs backwards across restarts. */
    val hlc = Hlc()

    val study = StudyRepository(
        database, hlc,
        deviceId = { prefs.cachedDeviceIdOrNull() ?: "local" },
        onChanged = { syncEngine?.requestSync() },
    )
    val library = LibraryRepository(context, database, ::probeAudioDuration)
    val folderCopier = FolderCopier(context.contentResolver, library.importsRoot)

    private val _startup = MutableStateFlow<StartupState>(StartupState.Loading)
    val startup: StateFlow<StartupState> = _startup

    val player = PlayerController(context, scope) { paperId, seconds, articleId ->
        scope.launch { study.savePosition(paperId, seconds, articleId) }
    }

    private val playedMutex = Mutex()
    private var pendingPlaySeconds = 0.0

    init {
        player.onPlayed = { seconds -> scope.launch { accumulatePlay(seconds) } }
        player.onArticleFinished = { paper, articleId ->
            val article = paper.article(articleId)
            if (article != null) {
                scope.launch { study.addActivity(ActivityValue(listenedSeconds = article.duration)) }
            }
        }
        scope.launch { start() }
    }

    private suspend fun start() {
        try {
            prefs.deviceId()
            database.records().maxClock()?.let(hlc::observe)
            library.ensureDemo()
            val report = LegacyMigrator(context, database, library, prefs, hlc).runIfNeeded()
            val engine = SyncFactory.create(database.records(), prefs, hlc, scope, deviceName())
            syncEngine = engine
            syncReady.complete(engine)
            _startup.value = StartupState.Ready(report)
            engine.requestSync()
        } catch (error: Exception) {
            _startup.value = StartupState.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Batches played seconds into the activity row every ~30 s instead of every tick. */
    private suspend fun accumulatePlay(seconds: Double) {
        val flush = playedMutex.withLock {
            pendingPlaySeconds += seconds
            if (pendingPlaySeconds >= 30) pendingPlaySeconds.also { pendingPlaySeconds = 0.0 } else 0.0
        }
        if (flush > 0) study.addActivity(ActivityValue(playSeconds = flush))
    }

    suspend fun flushActivity() {
        val flush = playedMutex.withLock { pendingPlaySeconds.also { pendingPlaySeconds = 0.0 } }
        if (flush > 0) study.addActivity(ActivityValue(playSeconds = flush))
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(64).ifBlank { "Android" }
}

sealed interface StartupState {
    data object Loading : StartupState
    data class Ready(val migration: MigrationReport?) : StartupState
    data class Failed(val message: String) : StartupState
}
