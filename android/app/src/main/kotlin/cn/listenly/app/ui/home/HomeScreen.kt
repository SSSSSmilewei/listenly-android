package cn.listenly.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.listenly.app.ui.glass.ContinuousShape
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.playback.PlaybackMode
import cn.listenly.app.playback.Speeds
import cn.listenly.app.ui.AppViewModel
import cn.listenly.app.ui.components.AppIcons
import cn.listenly.app.ui.components.BareIconButton
import cn.listenly.app.ui.components.GlassCard
import cn.listenly.app.ui.components.GlassPill
import cn.listenly.app.ui.components.LargeHeader
import cn.listenly.app.ui.components.PlayButton
import cn.listenly.app.ui.components.SectionLabel
import cn.listenly.app.ui.components.SeekBar
import cn.listenly.app.ui.components.StatStrip
import cn.listenly.app.ui.components.formatTime
import cn.listenly.app.ui.glass.GlassBox
import cn.listenly.app.ui.glass.GlassIconButton
import cn.listenly.app.ui.theme.Listenly
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun HomeScreen(
    vm: AppViewModel,
    contentPadding: PaddingValues,
    onImport: () -> Unit,
    onOpenPractice: (Paper, Article) -> Unit,
) {
    val paper by vm.selectedPaper.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val completions by vm.completions.collectAsStateWithLifecycle()
    val focusId by vm.focusArticleId.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val colors = Listenly.colors

    val isCurrent = paper != null && playback.paperId == paper?.id
    val current: Article? = paper?.let { p ->
        (if (isCurrent) playback.articleId?.let(p::article) else null) ?: p.article(focusId ?: "") ?: p.articles.firstOrNull()
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LargeHeader("听力") {
                GlassIconButton(if (colors.isDark) AppIcons.Sun else AppIcons.Moon, if (colors.isDark) "切换浅色模式" else "切换夜间模式", { vm.toggleDark(systemDark) })
                GlassIconButton(AppIcons.Plus, "导入材料", onImport)
            }
        }
        val p = paper
        if (p == null) {
            item { EmptyLibrary(onImport) }
            return@LazyColumn
        }
        item {
            NowPlayingCard(
                vm = vm,
                paper = p,
                article = current,
                isCurrent = isCurrent,
                playing = isCurrent && playback.isPlaying,
                duration = if (isCurrent) playback.durationSeconds.takeIf { it > 0 } ?: p.duration else p.duration,
                speed = playback.speed,
                mode = playback.mode,
                onOpenPractice = onOpenPractice,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        item {
            StatStrip(
                listOf(
                    activity.todayListenedMinutes.toString() to "今日听力 · 分钟",
                    activity.todayArticles.toString() to "今日完成 · 篇",
                    activity.streakDays.toString() to "连续学习 · 天",
                ),
                Modifier.padding(horizontal = 14.dp),
            )
        }
        item { SectionLabel("本套篇章", Modifier.padding(horizontal = 14.dp)) }
        item {
            GlassCard(Modifier.padding(horizontal = 14.dp), padding = PaddingValues(6.dp)) {
                p.articles.forEachIndexed { index, article ->
                    ArticleRow(
                        index = index,
                        article = article,
                        active = article.id == current?.id,
                        playing = isCurrent && playback.isPlaying && article.id == current?.id,
                        done = completions.containsKey("${p.id}/${article.id}"),
                        onPlay = { vm.playArticle(p, article, fromStart = article.id != current?.id) },
                        onOpen = { onOpenPractice(p, article) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun NowPlayingCard(
    vm: AppViewModel,
    paper: Paper,
    article: Article?,
    isCurrent: Boolean,
    playing: Boolean,
    duration: Double,
    speed: Float,
    mode: PlaybackMode,
    onOpenPractice: (Paper, Article) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Listenly.colors
    val index = paper.articles.indexOf(article)
    GlassCard(modifier, radius = 28.dp, padding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${paper.collection.title} · 第 ${index + 1} / ${paper.articles.size} 篇", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.accentText)
                Text(article?.title ?: paper.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = colors.text, lineHeight = 25.sp)
                Text(paper.title, fontSize = 13.sp, color = colors.textSecondary)
            }
            if (article != null) GlassPill("精听", { onOpenPractice(paper, article) }, color = colors.accentText, height = 34.dp, modifier = Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.height(10.dp))
        SeekBar(
            position = { if (isCurrent) vm.player.positionSeconds() else article?.start ?: 0.0 },
            duration = duration,
            playing = playing,
            onSeek = { target -> if (isCurrent) vm.seek(target) else vm.prepare(paper, article).invokeOnCompletion { vm.seek(target) } },
            onScrubbing = { vm.player.scrubbing = it },
            marks = paper.articles.drop(1).map { it.start },
            coarse = if (isCurrent) vm.player.coarsePosition else null,
        )
        Row(Modifier.fillMaxWidth()) {
            TimeLabel(vm, isCurrent, article?.start ?: 0.0, Modifier.weight(1f))
            Text(formatTime(duration), fontSize = 12.sp, color = colors.textSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            GlassPill(Speeds.label(speed), vm::cycleSpeed, description = "倍速 ${Speeds.label(speed)}")
            BareIconButton(AppIcons.Rewind5, "后退 5 秒", { vm.skip(-5.0) }, iconSize = 26.dp)
            PlayButton(playing, { vm.togglePlay(paper) })
            BareIconButton(AppIcons.Forward5, "前进 5 秒", { vm.skip(5.0) }, iconSize = 26.dp)
            GlassBox(onClick = vm::cycleMode, shape = ContinuousShape(20.dp), modifier = Modifier.size(width = 60.dp, height = 40.dp)) {
                Icon(
                    when (mode) {
                        PlaybackMode.Sequential -> AppIcons.Sequential
                        PlaybackMode.RepeatOne -> AppIcons.RepeatOne
                        PlaybackMode.Shuffle -> AppIcons.Shuffle
                    },
                    contentDescription = "播放模式：${mode.label}",
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Leaf composable: the only thing that recomposes as the coarse playhead moves. */
@Composable
fun TimeLabel(vm: AppViewModel, live: Boolean, fallback: Double, modifier: Modifier = Modifier, suffix: String = "") {
    val position by vm.player.coarsePosition.collectAsStateWithLifecycle()
    val seconds = if (live) position else fallback
    Text(formatTime(seconds) + suffix, fontSize = 12.sp, color = Listenly.colors.textSecondary, modifier = modifier)
}

@Composable
private fun ArticleRow(
    index: Int,
    article: Article,
    active: Boolean,
    playing: Boolean,
    done: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = Listenly.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent, ContinuousShape(18.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(if (active) colors.accent else colors.text.copy(alpha = 0.06f), ContinuousShape(13.dp))
                .clickable(role = Role.Button, onClickLabel = if (playing) "正在播放" else "播放", onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, contentDescription = "播放第 ${index + 1} 篇", tint = colors.onAccent, modifier = Modifier.size(15.dp))
            } else {
                Text("${index + 1}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(article.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.text)
            val meta = buildList {
                add(formatTime(article.duration))
                if (article.questions.isNotEmpty()) add("${article.questions.size} 题")
                if (playing) add("正在播放") else if (done) add("已完成")
            }.joinToString(" · ")
            Text(meta, fontSize = 13.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    GlassCard(Modifier.padding(horizontal = 14.dp)) {
        Text("还没有材料", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Listenly.colors.text)
        Spacer(Modifier.height(6.dp))
        Text("点右上角的 ＋ 导入包含 paper.json 的文件夹。", fontSize = 14.sp, color = Listenly.colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        GlassPill("导入材料", onImport, color = Listenly.colors.accentText)
    }
}

