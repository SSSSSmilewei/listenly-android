package cn.listenly.app.data

import cn.listenly.app.data.db.isNewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HlcAndSummaryTest {
    @Test fun clockIsMonotonicEvenWhenWallClockStepsBack() {
        var now = 1_000L
        val hlc = Hlc(nowMillis = { now })
        val a = hlc.next()
        now = 500L
        val b = hlc.next()
        assertTrue(b > a)
        hlc.observe(Long.MAX_VALUE / 4)
        assertTrue(hlc.next() > Long.MAX_VALUE / 4)
        assertEquals(1_000L, Hlc.millisOf(a))
    }

    @Test fun lwwTieBreaksOnDevice() {
        assertTrue(isNewer(2, "a", 1, "z"))
        assertTrue(isNewer(1, "b", 1, "a"))
        assertFalse(isNewer(1, "a", 1, "a"))
        assertFalse(isNewer(1, "a", 2, "a"))
    }

    @Test fun summaryCountsTodayAndStreak() {
        val today = LocalDate.of(2026, 10, 22)
        val days = mapOf(
            today to ActivityValue(listenedSeconds = 125.0, playSeconds = 600.0, questions = 5, articles = 1),
            today.minusDays(1) to ActivityValue(questions = 2),
            today.minusDays(2) to ActivityValue(studySeconds = 3000.0),
            today.minusDays(4) to ActivityValue(questions = 9),
        )
        val s = StudyRepository.summarize(days, today)
        assertEquals(2, s.todayListenedMinutes)
        assertEquals(1, s.todayArticles)
        assertEquals(3, s.streakDays)
        assertEquals((600.0 + 3000.0) / 3600.0, s.totalHours, 1e-9)
        assertEquals(mapOf(today to 5, today.minusDays(1) to 2, today.minusDays(4) to 9), s.questionsByDay)
    }

    @Test fun streakSurvivesUntilTodayIsStudied() {
        val today = LocalDate.of(2026, 10, 22)
        val s = StudyRepository.summarize(mapOf(today.minusDays(1) to ActivityValue(questions = 1)), today)
        assertEquals(1, s.streakDays)
    }

    @Test fun likePrefixEscapesWildcards() {
        assertEquals("cet6\\_2026/a\\%1/", StudyRepository.likePrefix("cet6_2026/a%1/"))
    }
}
