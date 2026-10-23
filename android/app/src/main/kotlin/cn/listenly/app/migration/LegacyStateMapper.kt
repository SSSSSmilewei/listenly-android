package cn.listenly.app.migration

import cn.listenly.app.data.ActivityValue
import cn.listenly.app.data.AnswerValue
import cn.listenly.app.data.Collections
import cn.listenly.app.data.CompletionValue
import cn.listenly.app.data.PositionValue
import cn.listenly.app.data.SettingValue
import cn.listenly.app.data.StudyRepository
import cn.listenly.app.model.Paper
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate

/** One record to write, before clocks are stamped. */
data class MigratedRecord(val collection: String, val key: String, val value: String)

data class LegacyMigration(
    val records: List<MigratedRecord>,
    val selectedPaper: String?,
    val focusArticle: String?,
    val playbackMode: String?,
    val skipped: Int,
)

/**
 * Converts the 2.x `localStorage['listenly.state.v1']` JSON into 3.0 records.
 *
 * 2.x keyed things with `JSON.stringify([...])`: answers `[paper, article, question]`, bookmarks
 * `[paper, article]`, passage positions `[paper, article, 'intensive', 'passage']` (seconds inside
 * the article). Activity maps hold one number per local day. Anything unreadable is counted in
 * [LegacyMigration.skipped] and left out rather than guessed.
 */
object LegacyStateMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun map(stateJson: String, deviceId: String, paperLookup: (String) -> Paper?): LegacyMigration {
        val state = runCatching { json.parseToJsonElement(stateJson) as? JsonObject }.getOrNull()
            ?: return LegacyMigration(emptyList(), null, null, null, skipped = 1)
        val out = mutableListOf<MigratedRecord>()
        var skipped = 0

        val answers = mutableMapOf<String, AnswerValue>()
        state.obj("answers")?.forEach { (rawKey, value) ->
            val parts = keyParts(rawKey, 3)
            val answer = value as? JsonObject
            val choice = answer?.str("choice")
            if (parts == null || choice == null || parts.any { '/' in it }) {
                skipped++
                return@forEach
            }
            val v = AnswerValue(choice, answer.bool("submitted") == true, answer.num("at")?.toLong())
            val key = parts.joinToString("/")
            answers[key] = v
            out += record(Collections.ANSWER, key, AnswerValue.serializer(), v)
        }

        (state["bookmarks"] as? JsonArray)?.forEach { element ->
            val parts = (element as? JsonPrimitive)?.content?.let { keyParts(it, 2) }
            if (parts == null || parts.any { '/' in it }) skipped++ else out += MigratedRecord(Collections.BOOKMARK, parts.joinToString("/"), "{}")
        }

        val active = state.obj("active")
        val activePaper = active?.str("paper")
        val activeArticle = active?.str("article")
        // Positions were per article; 3.0 keeps one position per paper. Take the active article's.
        state.obj("positions")?.forEach { (rawKey, value) ->
            val parts = keyParts(rawKey, 4) ?: return@forEach
            if (parts[2] != "intensive" || parts[3] != "passage") return@forEach
            if (parts[0] != activePaper || parts[1] != activeArticle) return@forEach
            val article = paperLookup(parts[0])?.article(parts[1]) ?: return@forEach
            val seconds = (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: return@forEach
            out += record(
                Collections.POSITION, parts[0], PositionValue.serializer(),
                PositionValue(article.start + seconds.coerceAtMost(article.duration), article.id),
            )
        }

        state.obj("articleCompletions")?.forEach { (rawKey, value) ->
            val parts = keyParts(rawKey, 2)
            val last = (value as? JsonObject)?.num("last")?.toLong()
            if (parts == null || last == null || last <= 0) {
                skipped++
                return@forEach
            }
            val article = paperLookup(parts[0])?.article(parts[1])
            val scored = article?.questions.orEmpty().filter { it.answer != null }
            val correct = scored.count { answers["${parts[0]}/${parts[1]}/${it.id}"]?.choice == it.answer }
            out += record(
                Collections.COMPLETION, "${parts[0]}/${parts[1]}", CompletionValue.serializer(),
                CompletionValue(Instant.ofEpochMilli(last).toString(), correct, scored.size),
            )
        }

        val days = mutableMapOf<LocalDate, ActivityValue>()
        fun addSeconds(field: String, apply: (ActivityValue, Double) -> ActivityValue) {
            state.obj(field)?.forEach { (rawDay, value) ->
                val day = normalizeDay(rawDay)
                val seconds = (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
                if (day == null || seconds == null) {
                    if (seconds != null) skipped++
                    return@forEach
                }
                days[day] = apply(days[day] ?: ActivityValue(), seconds)
            }
        }
        fun addCounts(field: String, apply: (ActivityValue, Int) -> ActivityValue) {
            state.obj(field)?.forEach { (rawDay, value) ->
                val day = normalizeDay(rawDay) ?: return@forEach
                val count = when (value) {
                    is JsonObject -> value.values.sumOf { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { n -> n > 0 } ?: 0.0 }.toInt()
                    is JsonPrimitive -> value.doubleOrNull?.toInt() ?: 0
                    else -> 0
                }
                if (count > 0) days[day] = apply(days[day] ?: ActivityValue(), count)
            }
        }
        addSeconds("days") { a, s -> a.copy(playSeconds = a.playSeconds + s) }
        addSeconds("completedListeningDays") { a, s -> a.copy(listenedSeconds = a.listenedSeconds + s) }
        addSeconds("studyDays") { a, s -> a.copy(studySeconds = a.studySeconds + s) }
        addCounts("dailyQuestions") { a, n -> a.copy(questions = a.questions + n) }
        addCounts("dailyArticles") { a, n -> a.copy(articles = a.articles + n) }
        for ((day, value) in days) {
            if (!value.isEmpty) out += record(Collections.ACTIVITY, "$day/$deviceId", ActivityValue.serializer(), value)
        }

        state.str("theme")?.let { theme ->
            out += record(Collections.SETTING, "darkMode", SettingValue.serializer(), SettingValue(if (theme == "dark") "dark" else "light"))
        }
        (state["speed"] as? JsonPrimitive)?.doubleOrNull?.let { speed ->
            out += record(Collections.SETTING, "speed", SettingValue.serializer(), SettingValue(speed.toString()))
        }

        return LegacyMigration(
            records = out,
            selectedPaper = state.str("selectedPaper") ?: activePaper,
            focusArticle = (state["homeFocus"] as? JsonObject)?.str("article") ?: activeArticle,
            playbackMode = state.str("playbackMode"),
            skipped = skipped,
        )
    }

    /** Parses a `JSON.stringify([...])` key with exactly [size] non-empty string parts. */
    private fun keyParts(raw: String, size: Int): List<String>? {
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
        if (array.size != size) return null
        val parts = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        return parts.takeIf { it.all(String::isNotEmpty) }
    }

    /** 2.x wrote local `YYYY-MM-DD`; some old WebViews wrote `M/D/YYYY`. */
    fun normalizeDay(raw: String): LocalDate? {
        Regex("""^(\d{4})[-/](\d{1,2})[-/](\d{1,2})$""").matchEntire(raw)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        }
        Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""").matchEntire(raw)?.let { m ->
            var (mo, d, y) = m.destructured.toList().map(String::toInt)
            if (mo > 12 && d <= 12) mo = d.also { d = mo }
            return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
        }
        return null
    }

    private fun <T> record(collection: String, key: String, serializer: KSerializer<T>, value: T) =
        MigratedRecord(collection, key, StudyRepository.json.encodeToString(serializer, value))

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
}
