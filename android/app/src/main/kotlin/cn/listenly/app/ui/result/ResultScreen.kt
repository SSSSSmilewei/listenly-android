package cn.listenly.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.listenly.app.ui.glass.ContinuousShape
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.ui.components.AppIcons
import cn.listenly.app.ui.components.GlassCard
import cn.listenly.app.ui.components.SectionLabel
import cn.listenly.app.ui.glass.Floating
import cn.listenly.app.ui.glass.GlassIconButton
import cn.listenly.app.ui.practice.ActionButton
import cn.listenly.app.ui.practice.PracticeViewModel
import cn.listenly.app.ui.practice.score
import cn.listenly.app.ui.theme.Listenly
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(
    practice: PracticeViewModel,
    paper: Paper,
    onClose: () -> Unit,
    onRedo: () -> Unit,
    onReview: (Article) -> Unit,
) {
    val answers by practice.answers.collectAsStateWithLifecycle()
    val result = score(paper, answers)
    val colors = Listenly.colors
    val percent = if (result.scored == 0) 0 else result.correct * 100 / result.scored

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(AppIcons.Close, "关闭", onClose)
                    Text("整套成绩", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.weight(1f).padding(end = 44.dp), textAlign = TextAlign.Center)
                }
            }
            item {
                GlassCard(radius = 28.dp, padding = PaddingValues(vertical = 26.dp, horizontal = 20.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(148.dp).semantics { contentDescription = "正确率 $percent%" }, contentAlignment = Alignment.Center) {
                            val track = colors.text.copy(alpha = 0.08f)
                            val accent = colors.accent
                            Canvas(Modifier.fillMaxSize()) {
                                val stroke = 12.dp.toPx()
                                val inset = stroke / 2
                                val arcSize = Size(size.width - stroke, size.height - stroke)
                                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                                drawArc(accent, -90f, 360f * percent / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${result.correct}/${result.scored}", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = colors.text)
                                Text("正确率 $percent%", fontSize = 13.sp, color = colors.textSecondary)
                            }
                        }
                    }
                    Text(paper.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), textAlign = TextAlign.Center)
                    Text("${paper.articles.size} 篇 · 已提交 ${result.answered} / ${result.total} 题", fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
            item { SectionLabel("逐题回顾") }
            item {
                GlassCard(padding = PaddingValues(8.dp)) {
                    paper.articles.forEachIndexed { index, article ->
                        if (article.questions.isEmpty()) return@forEachIndexed
                        val right = article.questions.count { q -> answers["${article.id}/${q.id}"]?.let { it.submitted && it.choice == q.answer } == true }
                        Text("${article.title} · $right / ${article.questions.count { it.answer != null }}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp))
                        FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            article.questions.forEach { q ->
                                val a = answers["${article.id}/${q.id}"]?.takeIf { it.submitted }
                                val wrong = a != null && q.answer != null && a.choice != q.answer
                                val label = when {
                                    a == null -> "第 ${q.number} 题未提交"
                                    q.answer == null -> "第 ${q.number} 题不计分"
                                    wrong -> "第 ${q.number} 题错误"
                                    else -> "第 ${q.number} 题正确"
                                }
                                Box(
                                    Modifier.size(width = 52.dp, height = 40.dp)
                                        .background(if (wrong) colors.wrong else if (a == null) colors.text.copy(alpha = 0.06f) else colors.accentSoft, ContinuousShape(14.dp))
                                        .semantics { contentDescription = label },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (wrong) Icon(AppIcons.Wrong, null, tint = colors.onWrong, modifier = Modifier.size(10.dp).padding(end = 1.dp))
                                        Text("${q.number}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (wrong) colors.onWrong else colors.accentText)
                                    }
                                }
                            }
                        }
                        if (index < paper.articles.lastIndex) Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(colors.hairline))
                    }
                }
            }
            paper.articles.forEach { article ->
                article.questions.forEach { q ->
                    val a = answers["${article.id}/${q.id}"]?.takeIf { it.submitted }
                    if (a != null && q.answer != null && a.choice != q.answer) {
                        item(key = "w-${article.id}-${q.id}") {
                            GlassCard(padding = PaddingValues(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${article.title} · 第 ${q.number} 题", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onWrong, modifier = Modifier.background(colors.wrong, ContinuousShape(11.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                                    Text("你的答案 ${a.choice} · 正确答案 ${q.answer}", fontSize = 13.sp, color = colors.textSecondary)
                                }
                                q.explanation?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = colors.text, modifier = Modifier.padding(top = 8.dp))
                                }
                                Text(
                                    "回到这篇精听",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.accentText,
                                    modifier = Modifier.padding(top = 8.dp).clickableText { onReview(article) },
                                )
                            }
                        }
                    }
                }
            }
        }
        Floating("result-actions") {
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActionButton("重做本套", primary = false, enabled = true, modifier = Modifier.weight(1f)) {
                practice.resetPaper(paper)
                onRedo()
            }
            ActionButton("精听复盘", primary = true, enabled = true, modifier = Modifier.weight(1f)) { onReview(paper.articles.first()) }
        }
        }
    }
}

private fun Modifier.clickableText(onClick: () -> Unit) = clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp)
