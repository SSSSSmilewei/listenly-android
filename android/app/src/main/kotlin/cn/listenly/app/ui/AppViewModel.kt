package cn.listenly.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.listenly.app.AppContainer
import cn.listenly.app.StartupState
import cn.listenly.app.data.ActivitySummary
import cn.listenly.app.data.CompletionValue
import cn.listenly.app.data.TranscriptDisplay
import cn.listenly.app.importer.ImportException
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.playback.PlaybackMode
import cn.listenly.app.playback.PlaybackState
import cn.listenly.app.playback.Speeds
import cn.listenly.app.ui.theme.Accent
import cn.listenly.app.ui.theme.DarkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A short message shown in the floating glass toast. */
data class UiMessage(val text: String, val id: Long = System.nanoTime())

sealed interface ImportState {
    data object Idle : ImportState
    data class Copying(val files: Int, val megabytes: Long) : ImportState
    data object Validating : ImportState
}

/** App-wide state shared by every screen: library, playback, study stats and appearance. */
class AppViewModel(private val c: AppContainer) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    val startup: StateFlow<StartupState> = c.startup

    val papers: StateFlow<List<Paper>> = c.library.papers.stateIn(viewModelScope, sharing, emptyList())

    val selectedPaper: StateFlow<Paper?> = combine(papers, c.prefs.selectedPaper) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, sharing, null)

    val focusArticleId: StateFlow<String?> = c.prefs.focusArticle.stateIn(viewModelScope, sharing, null)

    val playback: StateFlow<PlaybackState> = c.player.state

    val bookmarks: StateFlow<Set<String>> = c.study.observeBookmarks().stateIn(viewModelScope, sharing, emptySet())
    val completions: StateFlow<Map<String, CompletionValue>> = c.study.observeCompletions().stateIn(viewModelScope, sharing, emptyMap())
    val activity: StateFlow<ActivitySummary> = c.study.observeActivity()
        .stateIn(viewModelScope, sharing, ActivitySummary(0, 0, 0, 0.0, 0, emptyMap()))

    val accent: StateFlow<Accent> = c.study.observeSetting("accent").map(Accent::fromId).stateIn(viewModelScope, SharingStarted.Eagerly, Accent.Default)
    val darkMode: StateFlow<DarkMode> = c.study.observeSetting("darkMode").map(DarkMode::fromId).stateIn(viewModelScope, SharingStarted.Eagerly, DarkMode.System)
    val display: StateFlow<TranscriptDisplay> = c.prefs.display.stateIn(viewModelScope, sharing, TranscriptDisplay.Hidden)

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import.asStateFlow()

    val player get() = c.player

    init {
        viewModelScope.launch {
            c.study.observeSetting("speed").first()?.toFloatOrNull()?.let { c.player.setSpeed(Speeds.normalize(it)) }
            c.prefs.playbackMode.first()?.let { c.player.setMode(PlaybackMode.fromId(it)) }
        }
        viewModelScope.launch {
            c.startup.first { it !is StartupState.Loading }.let { state ->
                if (state is StartupState.Ready) state.migration?.let { report ->
                    if (report.records > 0 || report.papers > 0) say("已从旧版本迁移 ${report.papers} 套材料与 ${report.records} 条学习记录")
                    if (report.failedFolders.isNotEmpty()) say("有 ${report.failedFolders.size} 个旧文件夹无法识别，已保留原文件")
                }
            }
        }
    }

    fun say(text: String) {
        _message.value = UiMessage(text)
    }

    fun clearMessage(id: Long) {
        if (_message.value?.id == id) _message.value = null
    }

    // ---- selection & playback --------------------------------------------------------------

    fun selectPaper(paper: Paper) = viewModelScope.launch { c.prefs.select(paper.id, null) }

    /** Plays [article] of [paper] from its start (or from the saved spot if it is the same article). */
    fun playArticle(paper: Paper, article: Article, fromStart: Boolean = false) = viewModelScope.launch {
        c.prefs.select(paper.id, article.id)
        val saved = c.study.position(paper.id)
        val start = if (!fromStart && saved?.articleId == article.id && saved.seconds < article.end - 1) saved.seconds else article.start
        c.player.load(paper, start, play = true)
    }

    /** Opens [paper] in the player without starting it, at the saved position. */
    fun prepare(paper: Paper, article: Article?) = viewModelScope.launch {
        if (c.player.currentPaper?.id == paper.id) return@launch
        val saved = c.study.position(paper.id)
        val start = when {
            article != null && saved?.articleId != article.id -> article.start
            saved != null -> saved.seconds
            else -> article?.start ?: 0.0
        }
        c.player.load(paper, start, play = false)
    }

    fun togglePlay(paper: Paper?) = viewModelScope.launch {
        if (paper != null && c.player.currentPaper?.id != paper.id) {
            val saved = c.study.position(paper.id)
            c.player.load(paper, saved?.seconds ?: paper.articles.first().start, play = true)
        } else {
            c.player.toggle()
        }
    }

    fun seek(seconds: Double) = c.player.seekTo(seconds)
    fun skip(delta: Double) = c.player.skip(delta)

    fun cycleSpeed() {
        val next = Speeds.next(playback.value.speed)
        c.player.setSpeed(next)
        viewModelScope.launch { c.study.setSetting("speed", next.toString()) }
    }

    fun cycleMode() {
        val next = playback.value.mode.next()
        c.player.setMode(next)
        viewModelScope.launch { c.prefs.setPlaybackMode(next.id) }
        say(next.label)
    }

    fun setDisplay(display: TranscriptDisplay) = viewModelScope.launch { c.prefs.setDisplay(display) }

    fun toggleBookmark(paper: Paper, article: Article) = viewModelScope.launch {
        c.study.setBookmark(paper.id, article.id, "${paper.id}/${article.id}" !in bookmarks.value)
    }

    // ---- appearance ------------------------------------------------------------------------

    fun setAccent(accent: Accent) = viewModelScope.launch { c.study.setSetting("accent", accent.id) }
    fun setDarkMode(mode: DarkMode) = viewModelScope.launch { c.study.setSetting("darkMode", mode.id) }

    fun toggleDark(systemDark: Boolean) {
        val dark = when (darkMode.value) {
            DarkMode.System -> systemDark
            DarkMode.Light -> false
            DarkMode.Dark -> true
        }
        setDarkMode(if (dark) DarkMode.Light else DarkMode.Dark)
    }

    // ---- import ----------------------------------------------------------------------------

    fun importTree(uri: Uri) = viewModelScope.launch {
        if (_import.value != ImportState.Idle) return@launch
        _import.value = ImportState.Copying(0, 0)
        var folder: java.io.File? = null
        try {
            folder = c.folderCopier.copy(uri) { p -> _import.value = ImportState.Copying(p.files, p.bytes / (1024 * 1024)) }
            _import.value = ImportState.Validating
            val imported = c.library.importFolder(folder)
            imported.firstOrNull()?.let { c.prefs.select(it.id, null) }
            say("已导入 ${imported.size} 套材料")
        } catch (error: ImportException) {
            folder?.deleteRecursively()
            say("导入失败：${error.issues.first()}" + if (error.issues.size > 1) "（共 ${error.issues.size} 个问题）" else "")
        } catch (error: Exception) {
            folder?.deleteRecursively()
            say("导入失败：${error.message ?: "无法读取所选文件夹"}")
        } finally {
            _import.value = ImportState.Idle
        }
    }

    fun today(): LocalDate = LocalDate.now()

    override fun onCleared() {
        c.player.checkpointNow()
        c.scope.launch { c.flushActivity() }
    }
}
