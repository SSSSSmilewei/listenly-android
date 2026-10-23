package cn.listenly.app.importer

import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.model.PaperCollection
import cn.listenly.app.model.Question
import cn.listenly.app.model.QuestionOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Read-only view of a folder being imported. Paths are `/`-separated and relative to its root. */
interface ImportSource {
    fun files(): List<String>
    fun readText(path: String): String

    /** Real playable duration in seconds, or null when the file cannot be decoded. */
    fun audioDuration(path: String): Double?

    /** How the parsed [Paper.audio] should point at [path] once stored (absolute file path, asset URI...). */
    fun audioLocation(path: String): String
}

class ImportException(val issues: List<String>) : Exception(issues.joinToString("\n"))

/**
 * Parses and validates every paper.json (schema v2) in a folder.
 *
 * Matches the 2.x rules in docs/import-format.md: one full recording per paper, increasing and
 * non-overlapping article marks inside the real recording, and — when a transcript is present —
 * real, strictly increasing sentence times inside the article. Nothing is guessed or spread evenly.
 * Any problem rejects the whole batch so a bad import never half-applies.
 */
class PaperParser(private val source: ImportSource) {

    private val issues = mutableListOf<String>()

    fun parse(): List<Paper> {
        val files = source.files()
        val fileSet = files.toSet()
        val papers = mutableListOf<Paper>()
        for (path in files.filter { it.endsWith(".json", ignoreCase = true) }.sorted()) {
            val root = readJson(path) as? JsonObject ?: continue
            val entries = when {
                root["papers"] is JsonArray -> (root["papers"] as JsonArray).mapNotNull { it as? JsonObject }
                root.int("schemaVersion") == 2 -> listOf(root)
                else -> emptyList()
            }
            for (entry in entries) parsePaper(entry, path, fileSet)?.let(papers::add)
        }
        papers.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            issues += "套卷编号「$it」在本次导入中重复出现。"
        }
        if (papers.isEmpty() && issues.isEmpty()) {
            issues += "没有找到 schemaVersion 为 2 的 paper.json。"
        }
        if (issues.isNotEmpty()) throw ImportException(issues.distinct())
        return papers
    }

    private fun parsePaper(json: JsonObject, manifestPath: String, files: Set<String>): Paper? {
        val before = issues.size
        val id = safeId(json.string("id"))
        val label = json.string("title") ?: json.string("id") ?: manifestPath
        if (id == null) {
            issues += "「$label」缺少有效的 id（字母或数字开头，只含字母、数字、_ . -，最长 160）。"
            return null
        }
        if (json.int("schemaVersion") != null && json.int("schemaVersion") != 2) {
            issues += "「$label」的 schemaVersion 不是 2。"
        }
        val baseDir = manifestPath.substringBeforeLast('/', "")

        val audioRef = (json["audio"] as? JsonPrimitive)?.contentOrNullSafe()
            ?: (json["audio"] as? JsonObject)?.string("url")
        val audioPath = audioRef?.let { resolve(baseDir, it) }
        var duration: Double? = null
        when {
            audioRef == null -> issues += "「$label」缺少整套音频 audio。"
            audioPath == null || audioPath !in files -> issues += "「$label」的音频「$audioRef」不在所选文件夹中。"
            else -> {
                duration = source.audioDuration(audioPath)
                if (duration == null || duration <= 0) issues += "「$label」的音频无法读取时长，可能已损坏或格式不受支持。"
            }
        }
        val declared = json["duration"]?.let { parseSeconds(it.primitiveValue()) }
        if (json["duration"] != null && (declared == null || declared <= 0)) issues += "「$label」的 duration 无效。"

        val articlesJson = json["articles"] as? JsonArray
        if (articlesJson == null || articlesJson.isEmpty()) {
            issues += "「$label」缺少篇章进度标记 articles。"
            return null
        }
        val total = duration ?: declared ?: 0.0
        val seenArticles = mutableSetOf<String>()
        var previousEnd = -1.0
        val articles = articlesJson.mapIndexedNotNull { index, element ->
            val article = element as? JsonObject
            if (article == null) {
                issues += "「$label」第 ${index + 1} 篇格式无效。"
                return@mapIndexedNotNull null
            }
            parseArticle(article, label, baseDir, files, total, previousEnd, seenArticles)?.also { previousEnd = it.end }
        }
        if (issues.size != before || audioPath == null || duration == null) return null

        val collection = (json["collection"] as? JsonObject)?.let { c ->
            val cid = safeId(c.string("id"))
            if (cid == null) {
                issues += "「$label」的 collection.id 无效。"
                null
            } else {
                PaperCollection(cid, c.string("title")?.take(80) ?: cid)
            }
        } ?: defaultCollection(id, json.bool("isDemo") == true)
        return Paper(
            id = id,
            title = (json.string("title") ?: id).take(160),
            year = json.int("year"),
            collection = collection,
            audio = source.audioLocation(audioPath),
            duration = duration,
            articles = articles,
            isDemo = json.bool("isDemo") == true,
        )
    }

    private fun parseArticle(
        json: JsonObject,
        paperLabel: String,
        baseDir: String,
        files: Set<String>,
        paperDuration: Double,
        previousEnd: Double,
        seen: MutableSet<String>,
    ): Article? {
        val id = safeId(json.string("id"))
        val label = "$paperLabel · ${json.string("title") ?: json.string("id") ?: "未命名篇章"}"
        if (id == null) {
            issues += "「$label」缺少有效的篇章 id。"
            return null
        }
        if (!seen.add(id)) issues += "「$label」的篇章 id「$id」重复。"
        val start = json["start"]?.let { parseSeconds(it.primitiveValue()) }
        val end = json["end"]?.let { parseSeconds(it.primitiveValue()) }
        if (start == null || end == null || end <= start || start < previousEnd || end > paperDuration + MARK_TOLERANCE) {
            issues += "「$label」进度标记无效、重叠或超出录音时长。"
            return null
        }
        val length = end - start
        val transcript = when (val t = json["transcript"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> rowsFromJson(t, label)
            is JsonPrimitive -> transcriptFile(t.content, baseDir, files, label)
            else -> {
                issues += "「$label」的 transcript 格式无效。"
                emptyList()
            }
        }
        validateTranscript(transcript, length, label)
        val questions = (json["questions"] as? JsonArray).orEmpty().mapIndexedNotNull { index, q ->
            parseQuestion(q as? JsonObject, index, label)
        }
        questions.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { issues += "「$label」的题目 id「$it」重复。" }
        return Article(
            id = id,
            title = (json.string("title") ?: id).take(160),
            start = start,
            end = end,
            transcript = transcript.map { it.toSentence() },
            questions = questions,
        )
    }

    private fun transcriptFile(ref: String, baseDir: String, files: Set<String>, label: String): List<RawSentence> {
        val path = resolve(baseDir, ref)
        if (path == null || path !in files) {
            issues += "「$label」的原文文件「$ref」不在所选文件夹中。"
            return emptyList()
        }
        val lower = path.lowercase()
        return when {
            lower.endsWith(".srt") || lower.endsWith(".vtt") -> mergeBilingualRows(parseSubtitle(readText(path), issues, path))
            lower.endsWith(".lrc") -> mergeBilingualRows(parseLrc(readText(path), issues, path))
            lower.endsWith(".json") -> when (val json = readJson(path)) {
                is JsonArray -> rowsFromJson(json, label)
                is JsonObject -> (json["segments"] ?: json["transcript"]).let {
                    if (it is JsonArray) rowsFromJson(it, label) else {
                        issues += "$path：字幕 JSON 必须包含句子数组。"
                        emptyList()
                    }
                }
                else -> emptyList()
            }
            else -> {
                issues += "「$label」的原文必须是 JSON、SRT、VTT 或 LRC。纯 TXT 没有时间，无法逐句定位。"
                emptyList()
            }
        }
    }

    private fun rowsFromJson(rows: JsonArray, label: String): List<RawSentence> = rows.mapIndexedNotNull { index, element ->
        val row = element as? JsonObject
        if (row == null) {
            issues += "「$label」第 ${index + 1} 句缺少原文和时间。"
            return@mapIndexedNotNull null
        }
        val en = (row.string("en") ?: row.string("text") ?: row.string("english") ?: "").trim()
        val zh = (row.string("zh") ?: row.string("translation") ?: row.string("chinese") ?: "").trim()
        val startValue = row["start"] ?: row["startTime"]
        val endValue = row["end"] ?: row["endTime"]
        val start = startValue?.let { parseSeconds(it.primitiveValue()) }
        val end = endValue?.let { parseSeconds(it.primitiveValue()) }
        if ((startValue != null && start == null) || (endValue != null && end == null)) {
            issues += "「$label」第 ${index + 1} 句的时间无效。"
        }
        RawSentence(start, end, en, zh, timed = row.bool("timed") != false && start != null)
    }

    private fun validateTranscript(rows: List<RawSentence>, length: Double, label: String) {
        if (rows.isEmpty()) return
        if (rows.any { !it.timed }) {
            issues += "「$label」有未计时的分句。请提供逐句 SRT／LRC 或带 start 的 JSON 原文，应用不会平均分配时间。"
            return
        }
        if (rows.any { it.en.isBlank() }) issues += "「$label」有分句缺少英文原文。"
        var previous = -1.0
        rows.forEachIndexed { index, row ->
            val start = row.start!!
            val end = row.end
            if (start <= previous || start >= length || (end != null && (end <= start || end > length + MARK_TOLERANCE))) {
                issues += "「$label」第 ${index + 1} 句的时间重复、倒序或超出篇章标记。"
                return
            }
            previous = start
        }
    }

    private fun parseQuestion(json: JsonObject?, index: Int, label: String): Question? {
        if (json == null) {
            issues += "「$label」第 ${index + 1} 题格式无效。"
            return null
        }
        val id = safeId(json.string("id"))
        if (id == null) {
            issues += "「$label」第 ${index + 1} 题缺少有效 id。"
            return null
        }
        val options = (json["options"] as? JsonArray).orEmpty().mapNotNull { option ->
            val o = option as? JsonObject ?: return@mapNotNull null
            val oid = o.string("id")?.takeIf { it.isNotBlank() && it.length <= 8 } ?: return@mapNotNull null
            QuestionOption(oid, o.string("text").orEmpty())
        }
        if (options.size < 2) issues += "「$label」第 ${index + 1} 题至少需要两个选项。"
        val answer = json.string("answer")
        if (answer != null && options.none { it.id == answer }) issues += "「$label」第 ${index + 1} 题的答案「$answer」不在选项中。"
        return Question(
            id = id,
            number = json.int("number") ?: (index + 1),
            prompt = json.string("prompt"),
            options = options,
            answer = answer,
            explanation = json.string("explanation"),
        )
    }

    private fun readText(path: String): String = runCatching { source.readText(path) }.getOrElse {
        issues += "$path：无法读取（${it.message ?: "未知错误"}）。"
        ""
    }

    private fun readJson(path: String): JsonElement? {
        val text = readText(path).removePrefix("﻿")
        if (text.isBlank()) return null
        return runCatching { lenientJson.parseToJsonElement(text) }.getOrElse {
            issues += "$path：不是有效的 JSON。"
            null
        }
    }

    companion object {
        /** Tolerance for marks that end a few ms past the decoder-reported duration. */
        const val MARK_TOLERANCE = 0.05

        private val ID = Regex("""^[\p{L}\p{N}][\p{L}\p{N}_.-]*$""")
        private val lenientJson = Json { isLenient = false; ignoreUnknownKeys = true }

        fun safeId(value: String?): String? = value?.takeIf { it.length in 1..160 && ID.matches(it) }

        /** Resolves [ref] relative to [baseDir]; refuses absolute paths, `..`, drive letters and control chars. */
        fun resolve(baseDir: String, ref: String): String? {
            if (ref.isEmpty() || '\u0000' in ref) return null
            val decoded = runCatching { java.net.URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            val path = decoded.replace('\\', '/')
            if (path.startsWith("/") || path.any { it == ':' || it.code < 0x20 }) return null
            val parts = path.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return if (baseDir.isEmpty()) path else "$baseDir/$path"
        }

        fun defaultCollection(paperId: String, isDemo: Boolean): PaperCollection {
            val kind = paperId.lowercase()
            return when {
                kind.startsWith("cet6") -> PaperCollection("cet6", "六级合集")
                kind.startsWith("ielts") -> PaperCollection("ielts", "雅思合集")
                kind.startsWith("toefl") -> PaperCollection("toefl", "托福合集")
                isDemo -> PaperCollection("original", "原创合集")
                else -> PaperCollection("imported", "导入合集")
            }
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

/** Numbers stay numbers, strings stay strings, so [parseSeconds] can tell `12.5` from `"00:12.5"`. */
private fun JsonElement.primitiveValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else doubleOrNull
    else -> null
}
