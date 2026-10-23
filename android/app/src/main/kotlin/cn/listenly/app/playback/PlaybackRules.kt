package cn.listenly.app.playback

import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import kotlin.random.Random

/** Ids match 2.x (`playback-modes.js`) so migrated preferences keep their meaning. */
enum class PlaybackMode(val id: String, val label: String) {
    Sequential("sequential", "顺序播放"),
    RepeatOne("repeat", "单篇循环"),
    Shuffle("shuffle", "随机播放"),
    ;

    fun next(): PlaybackMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): PlaybackMode = entries.firstOrNull { it.id == id } ?: Sequential
    }
}

/** 1 → 1.25 → 1.5 → 1.75 → 2 → 1, as in 1.0.13+. */
object Speeds {
    val all = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
    fun next(current: Float): Float = all[(all.indexOfFirst { it >= current - 0.01f }.coerceAtLeast(0) + 1) % all.size]
    fun normalize(value: Float?): Float = all.firstOrNull { value != null && kotlin.math.abs(it - value) < 0.01f } ?: 1f
    fun label(value: Float): String = if (value % 1f == 0f) "${value.toInt()}×" else "${value}×"
}

sealed interface BoundaryAction {
    data object None : BoundaryAction
    data object Pause : BoundaryAction
    data class SeekTo(val seconds: Double) : BoundaryAction
}

/**
 * What happens when playback reaches the end of [current] inside a single full recording.
 * Pure so it can be unit-tested; the controller only executes the returned action.
 */
fun articleEndAction(
    paper: Paper,
    current: Article,
    mode: PlaybackMode,
    random: Random = Random.Default,
): BoundaryAction {
    val index = paper.articles.indexOfFirst { it.id == current.id }
    if (index < 0) return BoundaryAction.None
    return when (mode) {
        PlaybackMode.RepeatOne -> BoundaryAction.SeekTo(current.start)
        PlaybackMode.Shuffle -> {
            val others = paper.articles.filterIndexed { i, _ -> i != index }
            if (others.isEmpty()) BoundaryAction.SeekTo(current.start) else BoundaryAction.SeekTo(others[random.nextInt(others.size)].start)
        }
        PlaybackMode.Sequential -> paper.articles.getOrNull(index + 1)?.let { BoundaryAction.SeekTo(it.start) } ?: BoundaryAction.Pause
    }
}

/** Where "previous sentence" should go: restart the current one unless we are within its first 1.2 s. */
fun previousSentenceTarget(article: Article, seconds: Double): Double? {
    if (article.transcript.isEmpty()) return null
    val index = article.sentenceAt(seconds).coerceAtLeast(0)
    val start = article.sentenceStart(index)
    return if (seconds - start > 1.2 || index == 0) start else article.sentenceStart(index - 1)
}

fun nextSentenceTarget(article: Article, seconds: Double): Double? {
    if (article.transcript.isEmpty()) return null
    val index = article.sentenceAt(seconds)
    return if (index + 1 <= article.transcript.lastIndex) article.sentenceStart(index + 1) else null
}

/** End of sentence [index] in absolute recording time. */
fun sentenceEnd(article: Article, index: Int): Double {
    val sentence = article.transcript[index]
    val next = article.transcript.getOrNull(index + 1)?.start
    val relative = sentence.end ?: next ?: article.duration
    return article.start + minOf(relative, next ?: relative, article.duration)
}
