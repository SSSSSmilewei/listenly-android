package cn.listenly.app.model

import kotlinx.serialization.Serializable

/**
 * One exam set: a single full recording split into timed articles.
 *
 * All times are seconds. [Article.start]/[Article.end] are positions in the full recording;
 * [Sentence.start]/[Sentence.end] are relative to their article, exactly as in paper.json v2.
 */
@Serializable
data class Paper(
    val id: String,
    val title: String,
    val year: Int?,
    val collection: PaperCollection,
    /** Absolute path of the recording on this device, or an `asset:///` path for bundled material. */
    val audio: String,
    val duration: Double,
    val articles: List<Article>,
    val isDemo: Boolean = false,
) {
    val questionCount: Int get() = articles.sumOf { it.questions.size }

    fun article(id: String): Article? = articles.firstOrNull { it.id == id }

    /** The article playing at [seconds] in the full recording, or the nearest preceding one in a gap. */
    fun articleAt(seconds: Double): Article? =
        articles.lastOrNull { it.start <= seconds + TIME_EPSILON } ?: articles.firstOrNull()

    companion object {
        const val TIME_EPSILON = 0.001
    }
}

@Serializable
data class PaperCollection(val id: String, val title: String)

@Serializable
data class Article(
    val id: String,
    val title: String,
    val start: Double,
    val end: Double,
    val transcript: List<Sentence>,
    val questions: List<Question>,
) {
    val duration: Double get() = end - start

    /** Absolute recording time where sentence [index] begins. */
    fun sentenceStart(index: Int): Double = start + transcript[index].start

    /** Index of the sentence playing at absolute recording time [seconds], or -1 outside this article. */
    fun sentenceAt(seconds: Double): Int {
        if (seconds < start - Paper.TIME_EPSILON || seconds > end + Paper.TIME_EPSILON) return -1
        val relative = seconds - start
        var low = 0
        var high = transcript.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (transcript[mid].start <= relative + Paper.TIME_EPSILON) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}

@Serializable
data class Sentence(
    val start: Double,
    /** Null when the source omits it; the sentence then runs until the next one (or article end). */
    val end: Double?,
    val en: String,
    val zh: String?,
)

@Serializable
data class Question(
    val id: String,
    val number: Int,
    val prompt: String?,
    val options: List<QuestionOption>,
    /** Null when the material has no answer key: the choice is kept but never scored. */
    val answer: String?,
    val explanation: String?,
)

@Serializable
data class QuestionOption(val id: String, val text: String)
