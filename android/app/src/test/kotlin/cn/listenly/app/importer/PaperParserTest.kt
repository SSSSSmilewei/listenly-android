package cn.listenly.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PaperParserTest {

    private fun paperJson(articles: String, extra: String = "") = """
        {"schemaVersion":2,"id":"cet6-2026-1","title":"示例套卷","year":2026,
         "collection":{"id":"cet6","title":"六级合集"},"audio":"paper.mp3","duration":100$extra,
         "articles":[$articles]}
    """.trimIndent()

    private val good = """
        {"id":"a1","title":"Section A · 1","start":10,"end":40,
         "transcript":[{"start":0,"end":6,"en":"First.","zh":"第一句。"},{"start":6,"en":"Second."}],
         "questions":[{"id":"q1","number":1,"options":[{"id":"A","text":"x"},{"id":"B","text":"y"}],"answer":"B"}]},
        {"id":"a2","title":"Section A · 2","start":45,"end":80,"transcript":[],"questions":[]}
    """

    private fun parse(texts: Map<String, String>, audio: Map<String, Double?> = mapOf("paper.mp3" to 100.0)) =
        PaperParser(FakeSource(texts, audio)).parse()

    private fun issues(texts: Map<String, String>, audio: Map<String, Double?> = mapOf("paper.mp3" to 100.0)): List<String> =
        assertThrows(ImportException::class.java) { parse(texts, audio) }.issues

    @Test fun parsesAValidPaper() {
        val paper = parse(mapOf("paper.json" to paperJson(good))).single()
        assertEquals("cet6-2026-1", paper.id)
        assertEquals("六级合集", paper.collection.title)
        assertEquals(100.0, paper.duration, 1e-9)
        assertEquals("/data/imports/x/paper.mp3", paper.audio)
        val a1 = paper.articles[0]
        assertEquals(2, a1.transcript.size)
        assertNull(a1.transcript[1].end)
        assertEquals(16.0, a1.sentenceStart(1), 1e-9)
        assertEquals("B", a1.questions.single().answer)
    }

    @Test fun usesRealDecodedDurationNotTheDeclaredOne() {
        val paper = parse(mapOf("paper.json" to paperJson(good)), mapOf("paper.mp3" to 95.0)).single()
        assertEquals(95.0, paper.duration, 1e-9)
    }

    @Test fun rejectsMarksBeyondTheRealRecording() {
        assertTrue(issues(mapOf("paper.json" to paperJson(good)), mapOf("paper.mp3" to 60.0)).any { "超出录音" in it })
    }

    @Test fun rejectsOverlappingArticles() {
        val overlapping = """{"id":"a1","start":10,"end":50},{"id":"a2","start":40,"end":80}"""
        assertTrue(issues(mapOf("paper.json" to paperJson(overlapping))).any { "重叠" in it })
    }

    @Test fun rejectsUntimedTranscriptsInsteadOfSpreadingTime() {
        val untimed = """{"id":"a1","start":0,"end":30,"transcript":[{"en":"No time here."}]}"""
        assertTrue(issues(mapOf("paper.json" to paperJson(untimed))).any { "未计时" in it })
    }

    @Test fun rejectsNonIncreasingSentences() {
        val backwards = """{"id":"a1","start":0,"end":30,"transcript":[{"start":5,"en":"B"},{"start":2,"en":"A"}]}"""
        assertTrue(issues(mapOf("paper.json" to paperJson(backwards))).any { "倒序" in it })
    }

    @Test fun rejectsAudioOutsideTheFolderAndPathEscapes() {
        val escape = paperJson(good).replace("\"audio\":\"paper.mp3\"", "\"audio\":\"../secret.mp3\"")
        assertTrue(issues(mapOf("paper.json" to escape)).any { "不在所选文件夹" in it })
        assertNull(PaperParser.resolve("", "/etc/passwd"))
        assertNull(PaperParser.resolve("", "a/../../b"))
        assertNull(PaperParser.resolve("", "C:\\x.mp3"))
        assertEquals("sub/a.mp3", PaperParser.resolve("sub", "a.mp3"))
    }

    @Test fun rejectsUndecodableAudio() {
        assertTrue(issues(mapOf("paper.json" to paperJson(good)), mapOf("paper.mp3" to null)).any { "无法读取时长" in it })
    }

    @Test fun rejectsAnswerThatIsNotAnOption() {
        val bad = good.replace("\"answer\":\"B\"", "\"answer\":\"E\"")
        assertTrue(issues(mapOf("paper.json" to paperJson(bad))).any { "不在选项中" in it })
    }

    @Test fun readsTranscriptFromAnSrtFileNextToTheManifest() {
        val article = """{"id":"a1","start":0,"end":30,"transcript":"a1.srt"}"""
        val paper = parse(
            mapOf(
                "set/paper.json" to paperJson(article).replace("\"paper.mp3\"", "\"paper.mp3\""),
                "set/a1.srt" to "1\n00:00:00,000 --> 00:00:02,000\nHi.\n嗨。\n",
            ),
            mapOf("set/paper.mp3" to 100.0),
        ).single()
        assertEquals("Hi.", paper.articles.single().transcript.single().en)
    }

    @Test fun rejectsDuplicateIds() {
        val dup = """{"id":"a1","start":0,"end":10},{"id":"a1","start":20,"end":30}"""
        assertTrue(issues(mapOf("paper.json" to paperJson(dup))).any { "重复" in it })
    }

    @Test fun reportsWhenNoV2ManifestExists() {
        assertTrue(issues(mapOf("notes.json" to "{\"hello\":1}")).any { "schemaVersion" in it })
    }

    @Test fun parsesTheBundledOriginalDemo() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "examples/demo") }.first { it.isDirectory }
        val paper = PaperParser(FakeSource(mapOf("demo.json" to File(dir, "demo.json").readText()), mapOf("media/original-suite.wav" to 69.584))).parse().single()
        assertEquals("listenly-original", paper.id)
        assertEquals(2, paper.articles.size)
        assertTrue(paper.isDemo)
        assertEquals("原创合集", paper.collection.title)
        assertTrue(paper.articles.all { it.transcript.isNotEmpty() })
    }
}
