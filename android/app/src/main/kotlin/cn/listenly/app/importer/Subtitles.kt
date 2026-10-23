package cn.listenly.app.importer

import cn.listenly.app.model.Sentence

/** A transcript row before validation; [timed] is false when the source gave no usable start. */
data class RawSentence(
    val start: Double?,
    val end: Double?,
    val en: String,
    val zh: String,
    val timed: Boolean = start != null,
) {
    fun toSentence(): Sentence = Sentence(start ?: 0.0, end, en, zh.ifBlank { null })
}

private val HAN = Regex("[\\u3400-\\u9fff]")
private val CLOCK = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?$""")
private val PLAIN_NUMBER = Regex("""^\d+(?:\.\d+)?$""")
private val LRC_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val ANY_BRACKET = Regex("""\[[^]]*]""")
private val HTML_TAG = Regex("<[^>]*>")
private val BLANK_LINE = Regex("""\n\s*\n""")
private val VTT_HEADER = Regex("""^(WEBVTT|NOTE(\s|$)|STYLE(\s|$)|REGION(\s|$))""")

/**
 * Parses `12.5`, `"12.5"`, `"01:02"`, `"00:01:02,500"` or `"1:02.5"` to seconds.
 * Returns null for anything negative, non-finite or malformed.
 */
fun parseSeconds(value: Any?): Double? = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0 }
    is String -> {
        val text = value.trim()
        when {
            PLAIN_NUMBER.matches(text) -> text.toDouble()
            else -> CLOCK.matchEntire(text)?.let { match ->
                val (h, m, s, frac) = match.destructured
                if (m.toInt() > 59 || s.toInt() > 59) {
                    null
                } else {
                    (h.ifEmpty { "0" }.toInt() * 3600 + m.toInt() * 60 + s.toInt()) +
                        frac.padEnd(3, '0').ifEmpty { "0" }.toInt() / 1000.0
                }
            }
        }
    }
    else -> null
}

private fun String.hasHan() = HAN.containsMatchIn(this)

/** SRT and WebVTT. English and Chinese lines inside one cue become [RawSentence.en] / [RawSentence.zh]. */
fun parseSubtitle(text: String, issues: MutableList<String>, path: String): List<RawSentence> {
    val rows = mutableListOf<RawSentence>()
    val blocks = text.removePrefix("﻿").replace("\r", "").split(BLANK_LINE)
    for (block in blocks) {
        val lines = block.split('\n').filter { it.isNotEmpty() }
        val timeIndex = lines.indexOfFirst { "-->" in it }
        if (timeIndex < 0) {
            if (lines.isNotEmpty() && !VTT_HEADER.containsMatchIn(lines[0])) {
                issues += "$path：字幕缺少有效起止时间。"
            }
            continue
        }
        if (lines.count { "-->" in it } != 1) {
            issues += "$path：字幕分句之间需要空行分隔。"
            continue
        }
        val times = lines[timeIndex].split("-->")
        val start = parseSeconds(times[0].trim())
        val end = parseSeconds(times[1].trim().split(Regex("\\s")).first())
        if (start == null || end == null || end <= start) {
            issues += "$path：字幕起止时间无效。"
            continue
        }
        val body = lines.drop(timeIndex + 1).map { it.replace(HTML_TAG, "") }
        rows += RawSentence(
            start = start,
            end = end,
            en = body.filterNot { it.hasHan() }.joinToString("\n"),
            zh = body.filter { it.hasHan() }.joinToString("\n"),
        )
    }
    return rows.sortedBy { it.start }
}

/** LRC. Each line ends where the next distinct timestamp begins. */
fun parseLrc(text: String, issues: MutableList<String>, path: String): List<RawSentence> {
    val rows = mutableListOf<RawSentence>()
    for (line in text.removePrefix("﻿").lines()) {
        val tags = LRC_TAG.findAll(line).toList()
        val body = line.replace(ANY_BRACKET, "").trim()
        if (body.isEmpty()) continue
        if (tags.isEmpty()) {
            issues += "$path：LRC 原文缺少有效时间。"
            continue
        }
        for (tag in tags) {
            val (m, s, frac) = tag.destructured
            if (s.toInt() > 59) {
                issues += "$path：LRC 秒数必须小于 60。"
                continue
            }
            val start = m.toInt() * 60 + s.toInt() + frac.padEnd(3, '0').ifEmpty { "0" }.toInt() / 1000.0
            val chinese = body.hasHan()
            rows += RawSentence(start, null, if (chinese) "" else body, if (chinese) body else "")
        }
    }
    val sorted = rows.sortedBy { it.start }
    return sorted.mapIndexed { index, row ->
        val next = sorted.getOrNull(index + 1)?.start
        if (next != null && next > row.start!!) row.copy(end = next) else row
    }
}

/** Collapses an LRC/SRT that lists English and Chinese as separate cues at the same time into one row. */
fun mergeBilingualRows(rows: List<RawSentence>): List<RawSentence> {
    val merged = mutableListOf<RawSentence>()
    for (row in rows) {
        val last = merged.lastOrNull()
        if (last != null && last.timed && row.timed && last.start == row.start &&
            (last.en.isEmpty() != row.en.isEmpty()) && (last.zh.isEmpty() != row.zh.isEmpty())
        ) {
            merged[merged.lastIndex] = last.copy(
                en = last.en.ifEmpty { row.en },
                zh = last.zh.ifEmpty { row.zh },
                end = last.end ?: row.end,
            )
        } else {
            merged += row
        }
    }
    return merged
}
