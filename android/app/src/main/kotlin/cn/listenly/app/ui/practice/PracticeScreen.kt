package cn.listenly.app.ui.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.listenly.app.ui.glass.ContinuousShape
import cn.listenly.app.data.AnswerValue
import cn.listenly.app.data.TranscriptDisplay
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.model.Question
import cn.listenly.app.playback.Speeds
import cn.listenly.app.playback.nextSentenceTarget
import cn.listenly.app.playback.previousSentenceTarget
import cn.listenly.app.playback.sentenceEnd
import cn.listenly.app.ui.AppViewModel
import cn.listenly.app.ui.components.AppIcons
import cn.listenly.app.ui.components.BareIconButton
import cn.listenly.app.ui.components.Chip
import cn.listenly.app.ui.components.GlassCard
import cn.listenly.app.ui.components.GlassSegmented
import cn.listenly.app.ui.components.PlayButton
import cn.listenly.app.ui.components.SeekBar
import cn.listenly.app.ui.components.formatTime
import cn.listenly.app.ui.glass.Floating
import cn.listenly.app.ui.glass.GlassIconButton
import cn.listenly.app.ui.glass.liquidGlass
import cn.listenly.app.ui.home.TimeLabel
import cn.listenly.app.ui.theme.Listenly
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

enum class PracticeMode { Intensive, Exam }

@Composable
fun PracticeScreen(
    vm: AppViewModel,
    practice: PracticeViewModel,
    paper: Paper,
    initialArticleId: String,
    initialMode: PracticeMode,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val display by vm.display.collectAsStateWithLifecycle()
    val answers by practice.answers.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var pinnedArticleId by rememberSaveable { mutableStateOf(initialArticleId) }
    val colors = Listenly.colors

    val isCurrent = playback.paperId == paper.id
    // Reader and audio share one timeline: follow the playhead when this paper is playing.
    val article = (if (isCurrent) playback.articleId?.let(paper::article) else null)
        ?: paper.article(pinnedArticleId) ?: paper.articles.first()
    LaunchedEffect(article.id) { pinnedArticleId = article.id }
    LaunchedEffect(paper.id) { vm.prepare(paper, paper.article(initialArticleId)) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassIconButton(AppIcons.Back, "返回", onBack)
                GlassSegmented(listOf("精听", "做题"), mode.ordinal, { mode = PracticeMode.entries[it] }, Modifier.weight(1f))
                val saved = "${paper.id}/${article.id}" in bookmarks
                GlassIconButton(
                    if (saved) AppIcons.BookmarkFilled else AppIcons.Bookmark,
                    if (saved) "取消收藏" else "收藏",
                    { vm.toggleBookmark(paper, article) },
                    tint = if (saved) colors.accentText else colors.text,
                )
            }
            Column(Modifier.padding(start = 26.dp, end = 26.dp, top = 10.dp)) {
                val index = paper.articles.indexOf(article)
                val sub = if (mode == PracticeMode.Exam && article.questions.isNotEmpty()) {
                    val done = article.questions.count { answers["${article.id}/${it.id}"] != null }
                    " · 已答 $done / ${article.questions.size}"
                } else {
                    ""
                }
                Text("${paper.collection.title} · 第 ${index + 1} 篇$sub", fontSize = 12.sp, color = colors.textSecondary)
                Text(article.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.text, lineHeight = 26.sp)
            }
            when (mode) {
                PracticeMode.Intensive -> IntensivePane(vm, paper, article, display, isCurrent && playback.isPlaying, isCurrent)
                PracticeMode.Exam -> ExamPane(
                    practice = practice,
                    paper = paper,
                    article = article,
                    answers = answers,
                    onSubmitted = {
                        val next = paper.articles.getOrNull(paper.articles.indexOf(article) + 1)
                        if (next == null) {
                            onFinished()
                        } else {
                            pinnedArticleId = next.id
                            vm.prepare(paper, next)
                            if (isCurrent) vm.seek(next.start)
                        }
                    },
                    onSay = vm::say,
                )
            }
        }
        Floating("practice-player") {
            PracticePlayer(vm, paper, article, mode, isCurrent, playback.isPlaying && isCurrent, Modifier.align(Alignment.BottomCenter))
        }
    }
}

// ---- 精听 -------------------------------------------------------------------------------------

@Composable
private fun IntensivePane(
    vm: AppViewModel,
    paper: Paper,
    article: Article,
    display: TranscriptDisplay,
    playing: Boolean,
    isCurrent: Boolean,
) {
    val colors = Listenly.colors
    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TranscriptDisplay.entries.forEach { option ->
            Chip(option.label, display == option, { vm.setDisplay(option) })
        }
    }
    if (article.transcript.isEmpty()) {
        GlassCard(Modifier.padding(horizontal = 14.dp)) {
            Text("这篇材料没有带时间的原文。可以在做题模式作答，或补充 SRT／LRC 后按原编号重新导入。", fontSize = 14.sp, color = colors.textSecondary, lineHeight = 21.sp)
        }
        return
    }

    // The current sentence index changes a few times a minute; only that triggers recomposition.
    var current by remember(article.id) { mutableIntStateOf(if (isCurrent) article.sentenceAt(vm.player.positionSeconds()) else -1) }
    val latestCurrent by rememberUpdatedState(isCurrent)
    LaunchedEffect(article.id, playing) {
        do {
            if (latestCurrent) {
                val index = article.sentenceAt(vm.player.positionSeconds())
                if (index != current) current = index
            }
            if (playing) withFrameNanos { }
        } while (playing)
    }
    val listState = rememberLazyListState()
    AutoScroll(listState, current)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 200.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(article.transcript, key = { index, _ -> index }) { index, sentence ->
            val active = index == current
            val past = current >= 0 && index < current
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) (if (colors.isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.78f)) else Color.Transparent,
                        ContinuousShape(20.dp),
                    )
                    .clickable(role = Role.Button, onClickLabel = "从这句播放") {
                        vm.playArticle(paper, article).invokeOnCompletion { vm.seek(article.sentenceStart(index)) }
                    }
                    .padding(horizontal = 14.dp, vertical = if (active) 14.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (display) {
                    TranscriptDisplay.Hidden -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("第 ${index + 1} 句", fontSize = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) colors.accentText else colors.textSecondary)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f).height(8.dp).background(colors.text.copy(alpha = if (active) 0.14f else 0.07f), ContinuousShape(4.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(formatTime(sentence.start), fontSize = 12.sp, color = colors.textTertiary)
                    }
                    else -> {
                        if (active) {
                            val start = article.sentenceStart(index)
                            val end = sentenceEnd(article, index)
                            KaraokeText(
                                text = sentence.en,
                                progress = { ((vm.player.positionSeconds() - start) / (end - start).coerceAtLeast(0.01)).toFloat() },
                                playing = playing,
                                color = colors.text,
                                highlight = colors.accentText,
                                fontSize = 18.sp,
                                lineHeight = 27.sp,
                            )
                        } else {
                            Text(sentence.en, fontSize = 17.sp, lineHeight = 25.sp, color = if (past) colors.textTertiary else colors.text.copy(alpha = 0.88f))
                        }
                        if (display == TranscriptDisplay.Bilingual && !sentence.zh.isNullOrBlank()) {
                            Text(sentence.zh, fontSize = 14.sp, lineHeight = 21.sp, color = if (active) colors.textSecondary else colors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoScroll(state: LazyListState, index: Int) {
    LaunchedEffect(index) {
        if (index < 0 || state.isScrollInProgress) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index }
        val viewport = state.layoutInfo.viewportEndOffset
        if (item == null || item.offset < 0 || item.offset + item.size > viewport * 0.6) {
            state.animateScrollToItem(index, scrollOffset = -(viewport * 0.18).toInt())
        }
    }
}

// ---- 做题 -------------------------------------------------------------------------------------

@Composable
private fun ExamPane(
    practice: PracticeViewModel,
    paper: Paper,
    article: Article,
    answers: Map<String, AnswerValue>,
    onSubmitted: () -> Unit,
    onSay: (String) -> Unit,
) {
    val colors = Listenly.colors
    if (article.questions.isEmpty()) {
        GlassCard(Modifier.padding(14.dp)) {
            Text("这一篇没有题目。", fontSize = 14.sp, color = colors.textSecondary)
        }
        return
    }
    var open by remember(article.id) { mutableStateOf(setOf<String>()) }
    val submitted = article.questions.all { answers["${article.id}/${it.id}"]?.submitted == true }
    val allChosen = article.questions.all { answers["${article.id}/${it.id}"] != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(article.questions, key = { _, q -> q.id }) { _, q ->
            QuestionCard(
                question = q,
                answer = answers["${article.id}/${q.id}"],
                expanded = q.id in open,
                onToggle = { open = if (q.id in open) open - q.id else open + q.id },
                onChoose = { option ->
                    practice.choose(article, q.id, option)
                    open = open - q.id
                },
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            if (submitted) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("重做本篇", primary = false, enabled = true, modifier = Modifier.weight(1f)) { practice.reset(article) }
                    ActionButton(if (paper.articles.last().id == article.id) "查看成绩" else "下一篇", primary = true, enabled = true, modifier = Modifier.weight(1f)) { onSubmitted() }
                }
            } else {
                ActionButton("提交本篇", primary = true, enabled = allChosen, modifier = Modifier.fillMaxWidth()) {
                    practice.submit(paper, article) { ok -> if (ok) onSubmitted() else onSay("还有题目没有作答") }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: Question,
    answer: AnswerValue?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChoose: (String) -> Unit,
) {
    val colors = Listenly.colors
    val submitted = answer?.submitted == true
    val correct = submitted && question.answer != null && answer?.choice == question.answer
    val wrong = submitted && question.answer != null && answer?.choice != question.answer
    GlassCard(padding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val badgeColor = when {
                wrong -> colors.wrong
                correct || expanded -> colors.accent
                else -> colors.text.copy(alpha = 0.07f)
            }
            Box(Modifier.size(30.dp).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) {
                when {
                    wrong -> Icon(AppIcons.Wrong, "答错", tint = colors.onWrong, modifier = Modifier.size(14.dp))
                    correct -> Icon(AppIcons.Check, "答对", tint = colors.onAccent, modifier = Modifier.size(14.dp))
                    else -> Text("${question.number}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (expanded) colors.onAccent else colors.text)
                }
            }
            val chosen = question.options.firstOrNull { it.id == answer?.choice }
            Text(
                text = when {
                    chosen != null -> "${chosen.id}　${chosen.text}"
                    expanded -> "选择答案"
                    else -> "未作答"
                },
                fontSize = 15.sp,
                color = if (chosen != null) colors.text else colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                for (option in question.options) {
                    val selected = option.id == answer?.choice
                    val isKey = submitted && option.id == question.answer
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    isKey -> colors.accentSoft
                                    selected -> if (colors.isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.85f)
                                    else -> Color.Transparent
                                },
                                ContinuousShape(16.dp),
                            )
                            .clickable(enabled = !submitted, role = Role.RadioButton) { onChoose(option.id) }
                            .padding(12.dp)
                            .semantics { contentDescription = "选项 ${option.id}：${option.text}" + if (selected) "，已选" else "" },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(option.id, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected || isKey) colors.accentText else colors.textSecondary, modifier = Modifier.width(18.dp))
                        Text(option.text, fontSize = 15.sp, color = colors.text, lineHeight = 22.sp)
                    }
                }
                if (submitted && !question.explanation.isNullOrBlank()) {
                    Text(question.explanation, fontSize = 14.sp, lineHeight = 21.sp, color = colors.textSecondary, modifier = Modifier.padding(12.dp))
                }
                if (submitted && question.answer == null) {
                    Text("这道题没有标准答案，不计入正确率。", fontSize = 13.sp, color = colors.textTertiary, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
fun ActionButton(text: String, primary: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Listenly.colors
    val shape = ContinuousShape(28.dp)
    val base = modifier.height(56.dp)
    if (primary) {
        Box(
            base.background(if (enabled) colors.accent else colors.accent.copy(alpha = 0.45f), shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onAccent) }
    } else {
        Box(
            base.liquidGlass(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.text) }
    }
}

// ---- floating player --------------------------------------------------------------------------

@Composable
private fun PracticePlayer(
    vm: AppViewModel,
    paper: Paper,
    article: Article,
    mode: PracticeMode,
    isCurrent: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Listenly.colors
    val playback by vm.playback.collectAsStateWithLifecycle()
    val duration = playback.durationSeconds.takeIf { isCurrent && it > 0 } ?: paper.duration
    Column(
        modifier
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
            .fillMaxWidth()
            .liquidGlass(ContinuousShape(32.dp))
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        SeekBar(
            position = { if (isCurrent) vm.player.positionSeconds() else article.start },
            duration = duration,
            playing = playing,
            onSeek = { target -> if (isCurrent) vm.seek(target) else vm.prepare(paper, article).invokeOnCompletion { vm.seek(target) } },
            onScrubbing = { vm.player.scrubbing = it },
            marks = article.transcript.indices.drop(1).map(article::sentenceStart),
            trackHeight = 4.dp,
            coarse = if (isCurrent) vm.player.coarsePosition else null,
        )
        Row {
            TimeLabel(vm, isCurrent, article.start, Modifier.weight(1f))
            Text(formatTime(duration), fontSize = 12.sp, color = colors.textSecondary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.width(56.dp).height(44.dp).clickable(role = Role.Button, onClick = vm::cycleSpeed), contentAlignment = Alignment.Center) {
                Text(Speeds.label(playback.speed), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
            }
            if (mode == PracticeMode.Intensive) {
                BareIconButton(AppIcons.PrevSentence, "上一句", {
                    previousSentenceTarget(article, vm.player.positionSeconds())?.let(vm::seek)
                })
            } else {
                BareIconButton(AppIcons.Rewind5, "后退 5 秒", { vm.skip(-5.0) })
            }
            PlayButton(playing, { if (isCurrent) vm.togglePlay(paper) else vm.playArticle(paper, article) }, size = 54.dp)
            if (mode == PracticeMode.Intensive) {
                BareIconButton(AppIcons.NextSentence, "下一句", {
                    nextSentenceTarget(article, vm.player.positionSeconds())?.let(vm::seek)
                })
            } else {
                BareIconButton(AppIcons.Forward5, "前进 5 秒", { vm.skip(5.0) })
            }
            val looping = playback.sentenceLoop != null
            BareIconButton(
                AppIcons.RepeatOne,
                if (looping) "关闭单句循环" else "单句循环",
                {
                    if (looping) {
                        vm.player.setSentenceLoop(null)
                    } else {
                        val index = article.sentenceAt(vm.player.positionSeconds())
                        if (index >= 0) vm.player.setSentenceLoop(article.sentenceStart(index)..sentenceEnd(article, index))
                        else vm.say("没有可循环的分句")
                    }
                },
                tint = if (looping) colors.accentText else colors.text,
                iconSize = 22.dp,
            )
        }
    }
}

