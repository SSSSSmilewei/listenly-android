package cn.listenly.app.migration

import cn.listenly.app.data.ActivityValue
import cn.listenly.app.data.AnswerValue
import cn.listenly.app.data.Collections
import cn.listenly.app.data.CompletionValue
import cn.listenly.app.data.PositionValue
import cn.listenly.app.data.StudyRepository
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.model.PaperCollection
import cn.listenly.app.model.Question
import cn.listenly.app.model.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LegacyStateMapperTest {
    private val options = listOf(QuestionOption("A", "a"), QuestionOption("B", "b"))
    private val morning = Article(
        "morning", "Morning", 0.0, 34.4, emptyList(),
        listOf(Question("q1", 1, null, options, "B", null), Question("q2", 2, null, options, "A", null)),
    )
    private val paper = Paper("listenly-original", "Demo", null, PaperCollection("original", "原创合集"), "/a.wav", 69.5, listOf(morning))

    /** Shaped exactly like 2.x `localStorage['listenly.state.v1']`. */
    private val legacy = """
    {
      "theme":"dark","speed":1.25,"playbackMode":"repeat",
      "active":{"paper":"listenly-original","article":"morning"},
      "selectedPaper":"listenly-original",
      "answers":{
        "[\"listenly-original\",\"morning\",\"q1\"]":{"choice":"B","submitted":true,"at":1790000000000},
        "[\"listenly-original\",\"morning\",\"q2\"]":{"choice":"B","submitted":true,"at":1790000000000},
        "not-json":{"choice":"A"}
      },
      "bookmarks":["[\"listenly-original\",\"morning\"]","garbage"],
      "positions":{
        "[\"listenly-original\",\"morning\",\"intensive\",\"passage\"]":12.5,
        "[\"listenly-original\",\"other\",\"intensive\",\"passage\"]":3
      },
      "days":{"2026-10-20":600,"10/21/2026":120},
      "completedListeningDays":{"2026-10-20":300},
      "studyDays":{"2026-10-20":900},
      "dailyQuestions":{"2026-10-20":{"a":1,"b":1}},
      "dailyArticles":{"2026-10-20":{"x":1}},
      "articleCompletions":{"[\"listenly-original\",\"morning\"]":{"last":1790000000000,"count":1,"fingerprint":"f"}}
    }
    """

    private val result = LegacyStateMapper.map(legacy, "local_dev", mapOf(paper.id to paper)::get)
    private fun find(collection: String, key: String) = result.records.firstOrNull { it.collection == collection && it.key == key }

    @Test fun answersKeepChoiceAndSubmission() {
        val a = StudyRepository.json.decodeFromString(AnswerValue.serializer(), find(Collections.ANSWER, "listenly-original/morning/q1")!!.value)
        assertEquals(AnswerValue("B", true, 1790000000000), a)
        assertEquals(2, result.records.count { it.collection == Collections.ANSWER })
    }

    @Test fun bookmarksAndActivePositionBecomeAbsolute() {
        assertTrue(find(Collections.BOOKMARK, "listenly-original/morning") != null)
        val p = StudyRepository.json.decodeFromString(PositionValue.serializer(), find(Collections.POSITION, "listenly-original")!!.value)
        assertEquals(PositionValue(12.5, "morning"), p)
    }

    @Test fun completionIsScoredFromMigratedAnswers() {
        val c = StudyRepository.json.decodeFromString(CompletionValue.serializer(), find(Collections.COMPLETION, "listenly-original/morning")!!.value)
        assertEquals(1, c.correct)
        assertEquals(2, c.total)
    }

    @Test fun activityIsMergedPerDayAndKeyedToThisDevice() {
        val day = StudyRepository.json.decodeFromString(ActivityValue.serializer(), find(Collections.ACTIVITY, "2026-10-20/local_dev")!!.value)
        assertEquals(ActivityValue(listenedSeconds = 300.0, playSeconds = 600.0, studySeconds = 900.0, questions = 2, articles = 1), day)
        // Old WebViews wrote M/D/YYYY; it is normalized, not dropped.
        assertTrue(find(Collections.ACTIVITY, "2026-10-21/local_dev") != null)
    }

    @Test fun settingsAndSelectionCarryOver() {
        assertTrue(find(Collections.SETTING, "darkMode")!!.value.contains("dark"))
        assertTrue(find(Collections.SETTING, "speed")!!.value.contains("1.25"))
        assertEquals("listenly-original", result.selectedPaper)
        assertEquals("morning", result.focusArticle)
        assertEquals("repeat", result.playbackMode)
    }

    @Test fun unreadableEntriesAreCountedNotGuessed() {
        assertEquals(2, result.skipped) // "not-json" answer + "garbage" bookmark
    }

    @Test fun corruptStateYieldsNothing() {
        val empty = LegacyStateMapper.map("{oops", "d", { null })
        assertTrue(empty.records.isEmpty())
        assertNull(empty.selectedPaper)
    }

    @Test fun normalizesDayFormats() {
        assertEquals(LocalDate.of(2026, 3, 4), LegacyStateMapper.normalizeDay("2026-3-4"))
        assertEquals(LocalDate.of(2026, 3, 14), LegacyStateMapper.normalizeDay("14/3/2026"))
        assertNull(LegacyStateMapper.normalizeDay("2026-02-30"))
    }

    @Test fun decodesEvaluateJavascriptResults() {
        assertEquals("", LegacyMigrator.decodeJsResult("null"))
        assertEquals("{\"a\":1}", LegacyMigrator.decodeJsResult("\"{\\\"a\\\":1}\""))
    }
}
