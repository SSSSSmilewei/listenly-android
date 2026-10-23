package cn.listenly.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import cn.listenly.app.model.Paper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Slow-changing playback state for ordinary UI. The playhead itself is read per frame via [PlayerController.positionSeconds]. */
data class PlaybackState(
    val paperId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationSeconds: Double = 0.0,
    val speed: Float = 1f,
    val mode: PlaybackMode = PlaybackMode.Sequential,
    /** Absolute start/end of the sentence being looped, if 单句循环 is on. */
    val sentenceLoop: ClosedFloatingPointRange<Double>? = null,
    val error: String? = null,
    /** The article under the playhead; changes only at article boundaries. */
    val articleId: String? = null,
)

/**
 * Two-tier timing, after SPlayer-Next: [state] changes a few times per second and drives
 * recomposition; [positionSeconds] is read inside `withFrameNanos` loops by the playhead and the
 * karaoke highlight only, so 120 Hz animation never recomposes whole screens.
 */
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPositionCheckpoint: (paperId: String, seconds: Double, articleId: String?) -> Unit,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0.0)

    /** Coarse playhead (10 Hz) for small text labels only; collect it in the leaf that shows it. */
    val coarsePosition: StateFlow<Double> = _position.asStateFlow()

    private var controller: MediaController? = null
    private var paper: Paper? = null
    private var ticker: Job? = null
    private var lastCheckpoint = 0L

    /** Set while the user drags a seek bar so automatic article jumps never fight the finger. */
    @Volatile var scrubbing: Boolean = false

    /** Called once per natural article end, before the mode-specific jump; credits "listened" time. */
    var onArticleFinished: ((Paper, String) -> Unit)? = null

    /** Called every tick with seconds actually played since the last tick. */
    var onPlayed: ((Double) -> Unit)? = null

    val currentPaper: Paper? get() = paper

    suspend fun connect(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val created = MediaController.Builder(context, token).buildAsync().await()
        created.addListener(listener)
        controller = created
        syncState()
        return created
    }

    fun positionSeconds(): Double = controller?.currentPosition?.div(1000.0) ?: _position.value

    /** Loads [target] (if not already loaded) and seeks to [startSeconds]. */
    suspend fun load(target: Paper, startSeconds: Double, play: Boolean) {
        val c = connect()
        val sameSource = paper?.audio == target.audio && c.mediaItemCount > 0
        paper = target
        if (!sameSource) {
            c.setMediaItem(mediaItemFor(target), (startSeconds * 1000).toLong())
            c.prepare()
        } else {
            c.seekTo((startSeconds * 1000).toLong())
        }
        c.playWhenReady = play
        _position.value = startSeconds
        finishedArticleId = null
        _state.update { it.copy(paperId = target.id, sentenceLoop = null, error = null, articleId = target.articleAt(startSeconds)?.id) }
        lastArticleId = target.articleAt(startSeconds)?.id
        syncState()
    }

    fun play() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun pause() = controller?.pause()

    fun toggle() = if (controller?.isPlaying == true) pause() else play()

    fun seekTo(seconds: Double) {
        val c = controller ?: return
        c.seekTo((seconds.coerceIn(0.0, maxOf(0.0, _state.value.durationSeconds)) * 1000).toLong())
        _position.value = seconds
        lastArticleId = paper?.articleAt(seconds)?.id
        finishedArticleId = null
        publishArticle()
    }

    fun skip(deltaSeconds: Double) = seekTo(positionSeconds() + deltaSeconds)

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setMode(mode: PlaybackMode) = _state.update { it.copy(mode = mode) }

    fun setSentenceLoop(range: ClosedFloatingPointRange<Double>?) = _state.update { it.copy(sentenceLoop = range) }

    private var lastArticleId: String? = null
    private var finishedArticleId: String? = null
    private var lastTickPosition = -1.0

    private fun startTicker() {
        if (ticker?.isActive == true) return
        // MediaController must only be touched on the main (application) looper.
        ticker = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun tick() {
        val c = controller ?: return
        val p = paper ?: return
        val now = c.currentPosition / 1000.0
        if (c.isPlaying && lastTickPosition >= 0) {
            val played = now - lastTickPosition
            if (played > 0 && played < 2.0) onPlayed?.invoke(played)
        }
        lastTickPosition = now
        _position.value = now

        if (c.isPlaying && !scrubbing) {
            val loop = _state.value.sentenceLoop
            val previous = lastArticleId?.let(p::article)
            when {
                loop != null && now >= loop.endInclusive -> c.seekTo((loop.start * 1000).toLong())
                previous != null && now >= previous.end - END_SLOP && finishedArticleId != previous.id -> {
                    // Handle each article end once; resuming in trailing audio must not re-trigger it.
                    finishedArticleId = previous.id
                    onArticleFinished?.invoke(p, previous.id)
                    when (val action = articleEndAction(p, previous, _state.value.mode)) {
                        is BoundaryAction.SeekTo -> {
                            c.seekTo((action.seconds * 1000).toLong())
                            lastArticleId = p.articleAt(action.seconds)?.id
                            lastTickPosition = action.seconds
                            publishArticle()
                            return
                        }
                        BoundaryAction.Pause -> c.pause()
                        BoundaryAction.None -> Unit
                    }
                }
            }
        }
        lastArticleId = p.articleAt(now)?.id
        val finished = finishedArticleId?.let(p::article)
        if (finished == null || finished.id != lastArticleId || now < finished.end - END_SLOP - 0.5) finishedArticleId = null
        publishArticle()

        val wall = System.currentTimeMillis()
        if (wall - lastCheckpoint > CHECKPOINT_MS) {
            lastCheckpoint = wall
            onPositionCheckpoint(p.id, now, lastArticleId)
        }
    }

    private fun publishArticle() {
        if (_state.value.articleId != lastArticleId) _state.update { it.copy(articleId = lastArticleId) }
    }

    fun checkpointNow() {
        val p = paper ?: return
        val now = positionSeconds()
        onPositionCheckpoint(p.id, now, p.articleAt(now)?.id)
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncState()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) checkpointNow()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = "音频无法播放：${error.errorCodeName}") }
            // Recover in place, keeping the position, like the 1.0.4 fix in the WebView build.
            controller?.let { c ->
                val position = c.currentPosition
                c.prepare()
                c.seekTo(position)
            }
        }
    }

    private fun syncState() {
        val c = controller ?: return
        _state.update {
            it.copy(
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                durationSeconds = c.duration.takeIf { d -> d > 0 }?.div(1000.0) ?: paper?.duration ?: 0.0,
                speed = c.playbackParameters.speed,
            )
        }
        if (c.isPlaying) startTicker()
    }

    fun release() {
        checkpointNow()
        ticker?.cancel()
        controller?.release()
        controller = null
    }

    private fun mediaItemFor(paper: Paper): MediaItem {
        val uri = if (paper.audio.startsWith("asset:///")) Uri.parse(paper.audio) else Uri.fromFile(File(paper.audio))
        return MediaItem.Builder()
            .setMediaId(paper.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(paper.title)
                    .setArtist(paper.collection.title)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    companion object {
        private const val TICK_MS = 100L
        private const val CHECKPOINT_MS = 5_000L
        private const val END_SLOP = 0.08
    }
}
