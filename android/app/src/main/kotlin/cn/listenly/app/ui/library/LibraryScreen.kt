package cn.listenly.app.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.listenly.app.ui.glass.ContinuousShape
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.ui.AppViewModel
import cn.listenly.app.ui.components.AppIcons
import cn.listenly.app.ui.components.Chip
import cn.listenly.app.ui.components.GlassCard
import cn.listenly.app.ui.components.LargeHeader
import cn.listenly.app.ui.components.SectionLabel
import cn.listenly.app.ui.glass.GlassIconButton
import cn.listenly.app.ui.glass.liquidGlass
import cn.listenly.app.ui.theme.Listenly
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

private enum class LibraryFilter(val label: String) { All("全部"), Saved("收藏"), Unfinished("未完成") }

@Composable
fun LibraryScreen(
    vm: AppViewModel,
    contentPadding: PaddingValues,
    onImport: () -> Unit,
    onOpenPractice: (Paper, Article) -> Unit,
) {
    val papers by vm.papers.collectAsStateWithLifecycle()
    val selected by vm.selectedPaper.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val completions by vm.completions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var expanded by rememberSaveable { mutableStateOf(selected?.id) }
    val colors = Listenly.colors
    val years = remember(papers) { papers.mapNotNull { it.year }.distinct().sortedDescending() }

    val visible = papers.mapNotNull { paper ->
        if (year != null && paper.year != year) return@mapNotNull null
        val articles = paper.articles.filter { a ->
            val key = "${paper.id}/${a.id}"
            val text = query.isBlank() || "${paper.title} ${a.title} ${paper.year ?: ""}".contains(query.trim(), ignoreCase = true)
            text && when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Saved -> key in bookmarks
                LibraryFilter.Unfinished -> key !in completions && a.questions.isNotEmpty()
            }
        }
        if (articles.isEmpty()) null else paper to articles
    }.groupBy { it.first.collection }

    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LargeHeader("资料库") { GlassIconButton(AppIcons.Plus, "导入材料", onImport) }
        }
        item {
            Row(
                Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(44.dp).liquidGlass(ContinuousShape(22.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(AppIcons.Search, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("搜索套卷或篇章", fontSize = 15.sp, color = colors.textTertiary)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索套卷或篇章" },
                    )
                }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryFilter.entries.forEach { f -> Chip(f.label, filter == f, { filter = f }) }
                if (years.isNotEmpty()) {
                    Chip("全部年份", year == null, { year = null })
                    years.forEach { y -> Chip("$y", year == y, { year = y }) }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                GlassCard(Modifier.padding(horizontal = 14.dp)) {
                    Text(if (papers.isEmpty()) "还没有材料，点右上角 ＋ 导入。" else "没有符合条件的篇章。", fontSize = 14.sp, color = colors.textSecondary)
                }
            }
        }
        visible.forEach { (collection, entries) ->
            item(key = "c-${collection.id}") { SectionLabel("${collection.title} · ${entries.size} 套", Modifier.padding(horizontal = 14.dp)) }
            items(entries, key = { "p-${it.first.id}" }) { (paper, articles) ->
                PaperCard(
                    paper = paper,
                    articles = articles,
                    selected = paper.id == selected?.id,
                    expanded = expanded == paper.id || query.isNotBlank() || filter != LibraryFilter.All,
                    completedCount = paper.articles.count { "${paper.id}/${it.id}" in completions },
                    completions = completions.keys,
                    onSelect = { vm.selectPaper(paper) },
                    onToggle = { expanded = if (expanded == paper.id) null else paper.id },
                    onOpen = { onOpenPractice(paper, it) },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun PaperCard(
    paper: Paper,
    articles: List<Article>,
    selected: Boolean,
    expanded: Boolean,
    completedCount: Int,
    completions: Set<String>,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onOpen: (Article) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Listenly.colors
    GlassCard(modifier, padding = PaddingValues(6.dp)) {
        Row(
            Modifier.fillMaxWidth().background(if (selected) colors.accentSoft else Color.Transparent, ContinuousShape(18.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clickable(role = Role.RadioButton, onClickLabel = "设为当前套卷", onClick = onSelect).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(24.dp).background(if (selected) colors.accent else Color.Transparent, CircleShape)
                        .then(if (selected) Modifier else Modifier.background(colors.text.copy(alpha = 0.08f), CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(AppIcons.Check, "当前套卷", tint = colors.onAccent, modifier = Modifier.size(14.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(paper.title, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = colors.text)
                    Text(
                        "${paper.articles.size} 篇 · ${paper.questionCount} 题 · 已完成 $completedCount 篇" + (paper.year?.let { " · $it" } ?: ""),
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                }
            }
            Box(
                Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = if (expanded) "收起篇章" else "展开篇章", onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 48.dp, bottom = 2.dp)) {
                articles.forEachIndexed { index, article ->
                    val done = "${paper.id}/${article.id}" in completions
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(article) }.padding(top = 12.dp, bottom = 12.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(article.title, fontSize = 15.sp, color = colors.text, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                article.questions.isEmpty() -> "精听"
                                done -> "已完成"
                                else -> "未做"
                            },
                            fontSize = 12.sp,
                            fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (done) colors.accentText else colors.textTertiary,
                        )
                    }
                    if (index < articles.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                }
            }
        }
    }
}
