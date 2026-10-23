package cn.listenly.app.playback

import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.model.PaperCollection
import cn.listenly.app.model.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PlaybackRulesTest {
    private val a1 = Article("a1", "One", 10.0, 40.0, listOf(Sentence(0.0, null, "s1", null), Sentence(6.0, 13.0, "s2", null), Sentence(20.0, null, "s3", null)), emptyList())
    private val a2 = Article("a2", "Two", 45.0, 80.0, emptyList(), emptyList())
    private val a3 = Article("a3", "Three", 85.0, 99.0, emptyList(), emptyList())
    private val paper = Paper("p", "P", null, PaperCollection("c", "C"), "/x.mp3", 100.0, listOf(a1, a2, a3))

    @Test fun sequentialGoesToNextArticleAndPausesAfterTheLast() {
        assertEquals(BoundaryAction.SeekTo(45.0), articleEndAction(paper, a1, PlaybackMode.Sequential))
        assertEquals(BoundaryAction.Pause, articleEndAction(paper, a3, PlaybackMode.Sequential))
    }

    @Test fun repeatOneRestartsTheSameArticle() {
        assertEquals(BoundaryAction.SeekTo(45.0), articleEndAction(paper, a2, PlaybackMode.RepeatOne))
    }

    @Test fun shuffleNeverPicksTheCurrentArticle() {
        repeat(50) { seed ->
            val action = articleEndAction(paper, a2, PlaybackMode.Shuffle, Random(seed)) as BoundaryAction.SeekTo
            assert(action.seconds != 45.0)
        }
    }

    @Test fun sentenceLookupUsesAbsoluteTime() {
        assertEquals(0, a1.sentenceAt(10.0))
        assertEquals(1, a1.sentenceAt(16.5))
        assertEquals(2, a1.sentenceAt(39.9))
        assertEquals(-1, a1.sentenceAt(41.0))
        assertEquals(a1, paper.articleAt(42.0)) // gap belongs to the preceding article
    }

    @Test fun previousRestartsCurrentSentenceUnlessJustStarted() {
        assertEquals(16.0, previousSentenceTarget(a1, 18.0)!!, 1e-9)
        assertEquals(10.0, previousSentenceTarget(a1, 16.5)!!, 1e-9)
        assertEquals(30.0, nextSentenceTarget(a1, 18.0)!!, 1e-9)
        assertNull(nextSentenceTarget(a1, 31.0))
    }

    @Test fun sentenceEndPrefersExplicitEndThenNextStartThenArticleEnd() {
        assertEquals(16.0, sentenceEnd(a1, 0), 1e-9)
        assertEquals(23.0, sentenceEnd(a1, 1), 1e-9)
        assertEquals(40.0, sentenceEnd(a1, 2), 1e-9)
    }

    @Test fun speedCyclesAsIn2x() {
        assertEquals(1.25f, Speeds.next(1f))
        assertEquals(1f, Speeds.next(2f))
        assertEquals(1f, Speeds.normalize(0.75f)) // legacy 0.75 migrates to 1
        assertEquals("1.25×", Speeds.label(1.25f))
        assertEquals("2×", Speeds.label(2f))
        assertEquals(PlaybackMode.RepeatOne, PlaybackMode.fromId("repeat"))
    }
}
