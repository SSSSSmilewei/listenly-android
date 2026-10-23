package cn.listenly.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    @Test fun parsesSecondsInAllSupportedForms() {
        assertEquals(12.5, parseSeconds(12.5)!!, 1e-9)
        assertEquals(12.5, parseSeconds("12.5")!!, 1e-9)
        assertEquals(62.0, parseSeconds("01:02")!!, 1e-9)
        assertEquals(3662.5, parseSeconds("01:01:02,500")!!, 1e-9)
        assertEquals(62.05, parseSeconds("1:02.05")!!, 1e-9)
        assertNull(parseSeconds(-1))
        assertNull(parseSeconds("1:75"))
        assertNull(parseSeconds("abc"))
        assertNull(parseSeconds(Double.NaN))
    }

    @Test fun srtSplitsEnglishAndChineseLines() {
        val issues = mutableListOf<String>()
        val rows = parseSubtitle(
            "1\n00:00:01,000 --> 00:00:03,500\nHello there.\n你好。\n\n2\n00:00:03,500 --> 00:00:05,000\n<i>Bye.</i>\n",
            issues, "a.srt",
        )
        assertTrue(issues.isEmpty())
        assertEquals(2, rows.size)
        assertEquals("Hello there.", rows[0].en)
        assertEquals("你好。", rows[0].zh)
        assertEquals(3.5, rows[0].end!!, 1e-9)
        assertEquals("Bye.", rows[1].en)
    }

    @Test fun vttHeaderIsIgnoredButUntimedCueIsReported() {
        val issues = mutableListOf<String>()
        parseSubtitle("WEBVTT\n\nNOTE hi\n\nJust text without time\n", issues, "a.vtt")
        assertEquals(1, issues.size)
    }

    @Test fun lrcEndsEachLineAtTheNextTimestamp() {
        val issues = mutableListOf<String>()
        val rows = parseLrc("[00:01.00]One.\n[00:04.50]Two.\n[00:04.50]二。\n", issues, "a.lrc")
        assertTrue(issues.isEmpty())
        val merged = mergeBilingualRows(rows)
        assertEquals(2, merged.size)
        assertEquals(4.5, merged[0].end!!, 1e-9)
        assertEquals("Two.", merged[1].en)
        assertEquals("二。", merged[1].zh)
    }

    @Test fun lrcRejectsSecondsAbove59() {
        val issues = mutableListOf<String>()
        assertTrue(parseLrc("[00:61.00]Bad.\n", issues, "a.lrc").isEmpty())
        assertEquals(1, issues.size)
    }
}
