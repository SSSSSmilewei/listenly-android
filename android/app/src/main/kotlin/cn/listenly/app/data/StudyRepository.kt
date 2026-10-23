package cn.listenly.app.data

import androidx.room.withTransaction
import cn.listenly.app.data.db.ListenlyDatabase
import cn.listenly.app.data.db.RecordEntity
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/** Sync collection names; must match docs/v3/sync-protocol.md. */
object Collections {
    const val ANSWER = "answer"
    const val POSITION = "position"
    const val BOOKMARK = "bookmark"
    const val COMPLETION = "completion"
    const val ACTIVITY = "activity"
    const val SETTING = "setting"
}

@Serializable
data class AnswerValue(val choice: String, val submitted: Boolean = false, val at: Long? = null)

@Serializable
data class PositionValue(val seconds: Double, val articleId: String? = null)

@Serializable
data class CompletionValue(val completedAt: String, val correct: Int, val total: Int)

@Serializable
data class ActivityValue(
    /** Seconds credited only after an article was listened through ("今日听力分钟"). */
    val listenedSeconds: Double = 0.0,
    /** Seconds actually played, towards cumulative study hours. */
    val playSeconds: Double = 0.0,
    val studySeconds: Double = 0.0,
    val questions: Int = 0,
    val articles: Int = 0,
) {
    operator fun plus(other: ActivityValue) = ActivityValue(
        listenedSeconds + other.listenedSeconds,
        playSeconds + other.playSeconds,
        studySeconds + other.studySeconds,
        questions + other.questions,
        articles + other.articles,
    )

    val isEmpty: Boolean
        get() = listenedSeconds <= 0 && playSeconds <= 0 && studySeconds <= 0 && questions == 0 && articles == 0
}

@Serializable
data class SettingValue(val v: String)

data class ActivitySummary(
    val todayListenedMinutes: Int,
    val todayArticles: Int,
    val todayQuestions: Int,
    val totalHours: Double,
    val streakDays: Int,
    /** Day → number of questions answered, for the activity calendar. */
    val questionsByDay: Map<LocalDate, Int>,
)

/**
 * Typed access to the `records` table. Every write stamps a fresh [Hlc] clock and marks the row
 * dirty for the sync engine; nothing here knows whether sync is enabled.
 */
class StudyRepository(
    private val db: ListenlyDatabase,
    private val hlc: Hlc,
    private val deviceId: () -> String,
    private val today: () -> LocalDate = LocalDate::now,
    /** Called after every local write, e.g. to schedule a debounced sync. */
    private val onChanged: () -> Unit = {},
) {
    private val dao = db.records()

    // ---- answers ---------------------------------------------------------------------------

    fun observeAnswers(paperId: String, articleId: String): Flow<Map<String, AnswerValue>> =
        dao.observePrefix(Collections.ANSWER, likePrefix("$paperId/$articleId/")).map { rows ->
            rows.mapNotNull { row -> decodeOrNull(AnswerValue.serializer(), row.value)?.let { row.key.substringAfterLast('/') to it } }.toMap()
        }.distinctUntilChanged()

    fun observePaperAnswers(paperId: String): Flow<Map<String, AnswerValue>> =
        dao.observePrefix(Collections.ANSWER, likePrefix("$paperId/")).map { rows ->
            rows.mapNotNull { row -> decodeOrNull(AnswerValue.serializer(), row.value)?.let { row.key.removePrefix("$paperId/") to it } }.toMap()
        }.distinctUntilChanged()

    /** Choosing is allowed until the article is submitted. */
    suspend fun choose(paperId: String, articleId: String, questionId: String, choice: String) {
        val key = answerKey(paperId, articleId, questionId)
        val current = dao.get(Collections.ANSWER, key)?.takeIf { !it.deleted }?.let { decodeOrNull(AnswerValue.serializer(), it.value) }
        if (current?.submitted == true) return
        write(Collections.ANSWER, key, AnswerValue.serializer(), AnswerValue(choice))
    }

    /**
     * Submits every chosen answer in [article] at once ("提交本篇"), records completion and
     * activity. Returns false (and changes nothing) unless every question has a choice.
     */
    suspend fun submitArticle(paper: Paper, article: Article, now: Instant = Instant.now()): Boolean = db.withTransaction {
        val answers = article.questions.associate { q ->
            q.id to dao.get(Collections.ANSWER, answerKey(paper.id, article.id, q.id))
                ?.takeIf { !it.deleted }?.let { decodeOrNull(AnswerValue.serializer(), it.value) }
        }
        if (article.questions.isEmpty() || answers.values.any { it == null }) return@withTransaction false
        val newlySubmitted = answers.filterValues { it?.submitted == false }
        for ((questionId, answer) in newlySubmitted) {
            write(
                Collections.ANSWER, answerKey(paper.id, article.id, questionId), AnswerValue.serializer(),
                answer!!.copy(submitted = true, at = now.toEpochMilli()),
            )
        }
        val scored = article.questions.filter { it.answer != null }
        val correct = scored.count { answers[it.id]?.choice == it.answer }
        write(
            Collections.COMPLETION, "${paper.id}/${article.id}", CompletionValue.serializer(),
            CompletionValue(now.toString(), correct, scored.size),
        )
        if (newlySubmitted.isNotEmpty()) {
            addActivity(ActivityValue(questions = newlySubmitted.size, articles = 1))
        }
        true
    }

    /** "重做": clears this article's answers but keeps its completion history. */
    suspend fun resetArticle(paperId: String, article: Article) = db.withTransaction {
        for (q in article.questions) delete(Collections.ANSWER, answerKey(paperId, article.id, q.id))
    }

    // ---- positions -------------------------------------------------------------------------

    suspend fun position(paperId: String): PositionValue? =
        dao.get(Collections.POSITION, paperId)?.takeIf { !it.deleted }?.let { decodeOrNull(PositionValue.serializer(), it.value) }

    suspend fun savePosition(paperId: String, seconds: Double, articleId: String?) =
        write(Collections.POSITION, paperId, PositionValue.serializer(), PositionValue(seconds, articleId))

    // ---- bookmarks -------------------------------------------------------------------------

    fun observeBookmarks(): Flow<Set<String>> =
        dao.observeCollection(Collections.BOOKMARK).map { rows -> rows.map { it.key }.toSet() }.distinctUntilChanged()

    suspend fun setBookmark(paperId: String, articleId: String, saved: Boolean) {
        val key = "$paperId/$articleId"
        if (saved) write(Collections.BOOKMARK, key, null) else delete(Collections.BOOKMARK, key)
    }

    // ---- completions -----------------------------------------------------------------------

    fun observeCompletions(): Flow<Map<String, CompletionValue>> =
        dao.observeCollection(Collections.COMPLETION).map { rows ->
            rows.mapNotNull { row -> decodeOrNull(CompletionValue.serializer(), row.value)?.let { row.key to it } }.toMap()
        }.distinctUntilChanged()

    // ---- activity --------------------------------------------------------------------------

    /** Adds to today's row for THIS device. Per-device keys keep LWW sync from ever losing counts. */
    suspend fun addActivity(delta: ActivityValue, date: LocalDate = today()) {
        if (delta.isEmpty) return
        db.withTransaction {
            val key = "$date/${deviceId()}"
            val current = dao.get(Collections.ACTIVITY, key)?.takeIf { !it.deleted }
                ?.let { decodeOrNull(ActivityValue.serializer(), it.value) } ?: ActivityValue()
            write(Collections.ACTIVITY, key, ActivityValue.serializer(), current + delta)
        }
    }

    fun observeActivity(): Flow<ActivitySummary> =
        dao.observeCollection(Collections.ACTIVITY).map { rows ->
            val byDay = rows.mapNotNull { row ->
                val day = runCatching { LocalDate.parse(row.key.substringBefore('/')) }.getOrNull()
                val value = decodeOrNull(ActivityValue.serializer(), row.value)
                if (day == null || value == null) null else day to value
            }.groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> values.fold(ActivityValue(), ActivityValue::plus) }
            summarize(byDay, today())
        }

    // ---- settings --------------------------------------------------------------------------

    fun observeSetting(name: String): Flow<String?> =
        dao.observe(Collections.SETTING, name).map { row -> decodeOrNull(SettingValue.serializer(), row?.value)?.v }
            .distinctUntilChanged()

    suspend fun setSetting(name: String, value: String) =
        write(Collections.SETTING, name, SettingValue.serializer(), SettingValue(value))

    // ---- low level -------------------------------------------------------------------------

    private suspend fun <T> write(collection: String, key: String, serializer: KSerializer<T>, value: T) =
        write(collection, key, json.encodeToString(serializer, value))

    private suspend fun write(collection: String, key: String, value: String?) {
        dao.put(RecordEntity(collection, key, value ?: "{}", deleted = false, clock = hlc.next(), device = deviceId(), dirty = true))
        onChanged()
    }

    private suspend fun delete(collection: String, key: String) {
        val existing = dao.get(collection, key) ?: return
        if (existing.deleted) return
        dao.put(RecordEntity(collection, key, null, deleted = true, clock = hlc.next(), device = deviceId(), dirty = true))
        onChanged()
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun answerKey(paperId: String, articleId: String, questionId: String) = "$paperId/$articleId/$questionId"

        /** Escapes LIKE wildcards; ids may legally contain `_`. */
        fun likePrefix(prefix: String) = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /**
         * Remote records are only checked for being JSON objects by the server; one written by a
         * different or newer client must never crash the app, so it is skipped instead.
         */
        fun <T> decodeOrNull(serializer: KSerializer<T>, value: String?): T? =
            if (value == null) null else runCatching { json.decodeFromString(serializer, value) }.getOrNull()

        fun summarize(byDay: Map<LocalDate, ActivityValue>, today: LocalDate): ActivitySummary {
            val todayValue = byDay[today] ?: ActivityValue()
            var streak = 0
            var day = if ((byDay[today]?.isEmpty) == false) today else today.minusDays(1)
            while (byDay[day]?.isEmpty == false) {
                streak++
                day = day.minusDays(1)
            }
            val totalSeconds = byDay.values.sumOf { it.playSeconds + it.studySeconds }
            return ActivitySummary(
                todayListenedMinutes = (todayValue.listenedSeconds / 60).toInt(),
                todayArticles = todayValue.articles,
                todayQuestions = todayValue.questions,
                totalHours = totalSeconds / 3600.0,
                streakDays = streak,
                questionsByDay = byDay.mapValues { it.value.questions }.filterValues { it > 0 },
            )
        }
    }
}
