package cn.listenly.app.ui.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.listenly.app.ui.glass.ContinuousShape
import cn.listenly.app.BuildConfig
import cn.listenly.app.data.ActivitySummary
import cn.listenly.app.playback.Speeds
import cn.listenly.app.sync.SyncStatus
import cn.listenly.app.ui.AppViewModel
import cn.listenly.app.ui.components.AppIcons
import cn.listenly.app.ui.components.GlassCard
import cn.listenly.app.ui.components.GlassSegmented
import cn.listenly.app.ui.components.LargeHeader
import cn.listenly.app.ui.components.SectionLabel
import cn.listenly.app.ui.components.roundTo1
import cn.listenly.app.ui.glass.GlassBox
import cn.listenly.app.ui.glass.GlassIconButton
import cn.listenly.app.ui.theme.Accent
import cn.listenly.app.ui.theme.DarkMode
import cn.listenly.app.ui.theme.Listenly
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun ProfileScreen(
    vm: AppViewModel,
    syncStatus: SyncStatus,
    contentPadding: PaddingValues,
    onImport: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val activity by vm.activity.collectAsStateWithLifecycle()
    val accent by vm.accent.collectAsStateWithLifecycle()
    val darkMode by vm.darkMode.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val colors = Listenly.colors

    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LargeHeader("我的") {
                if (syncStatus !is SyncStatus.Unavailable) {
                    GlassBox(onClick = onOpenSync, shape = ContinuousShape(20.dp), modifier = Modifier.height(40.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(AppIcons.Sync, null, tint = colors.text, modifier = Modifier.size(18.dp))
                            Text(syncLabel(syncStatus), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.text)
                        }
                    }
                }
                GlassIconButton(AppIcons.Plus, "导入材料", onImport)
            }
        }
        item { StatsCard(activity, vm.today(), Modifier.padding(horizontal = 14.dp)) }
        item { SectionLabel("外观", Modifier.padding(horizontal = 14.dp)) }
        item {
            GlassCard(Modifier.padding(horizontal = 14.dp)) {
                Text("深色模式", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.text)
                GlassSegmented(
                    DarkMode.entries.map { it.label },
                    darkMode.ordinal,
                    { vm.setDarkMode(DarkMode.entries[it]) },
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    height = 40.dp,
                )
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.Bottom) {
                    Text("强调色", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.text, modifier = Modifier.weight(1f))
                    Text(accent.label, fontSize = 13.sp, color = colors.textSecondary)
                }
                AccentPicker(accent, vm::setAccent, Modifier.padding(top = 10.dp))
                if (accent == Accent.Monet && !Accent.monetAvailable) {
                    Text("此系统版本不支持壁纸取色，暂用默认配色。", fontSize = 12.sp, color = colors.textTertiary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            GlassCard(Modifier.padding(horizontal = 14.dp), padding = PaddingValues(vertical = 4.dp)) {
                if (syncStatus !is SyncStatus.Unavailable) {
                    SettingRow("同步与账号", syncLabel(syncStatus), onOpenSync)
                    Divider()
                }
                SettingRow("播放倍速", Speeds.label(playback.speed), vm::cycleSpeed)
                Divider()
                SettingRow("导入材料", "paper.json 文件夹", onImport)
                Divider()
                SettingRow("版本", BuildConfig.VERSION_NAME + if (BuildConfig.SYNC_ENABLED) "" else " · 完全离线", null)
            }
        }
    }
}

private fun syncLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Unavailable -> "离线版"
    SyncStatus.SignedOut -> "未登录"
    SyncStatus.Syncing -> "同步中"
    is SyncStatus.Idle -> "已同步"
    is SyncStatus.Failed -> "同步失败"
}

@Composable
private fun StatsCard(activity: ActivitySummary, today: LocalDate, modifier: Modifier = Modifier) {
    val colors = Listenly.colors
    GlassCard(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Stat("${activity.todayListenedMinutes}", "今日分钟", Modifier.weight(1f))
            Stat("${activity.todayArticles}", "今日完成篇", Modifier.weight(1f))
            Stat(activity.totalHours.roundTo1(), "累计小时", Modifier.weight(1f))
        }
        ActivityCalendar(activity.questionsByDay, today, Modifier.padding(top = 14.dp))
        Text("近 18 周做题活跃度", fontSize = 11.sp, color = colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Listenly.colors.text)
        Text(label, fontSize = 12.sp, color = Listenly.colors.textSecondary)
    }
}

/** 18 weeks × 7 days, newest column on the right, levels as in 2.x (1 / 4 / 10 / 20 questions). */
@Composable
private fun ActivityCalendar(byDay: Map<LocalDate, Int>, today: LocalDate, modifier: Modifier = Modifier) {
    val colors = Listenly.colors
    val weeks = 18
    // Columns are Sunday..Saturday weeks; the last column is the current week.
    val lastSaturday = today.plusDays((6 - today.dayOfWeek.value % 7).toLong())
    val start = lastSaturday.minusDays(weeks * 7L - 1)
    val total = byDay.filterKeys { !it.isBefore(start) }.values.sum()
    Row(
        modifier.fillMaxWidth().semantics { contentDescription = "近 $weeks 周共答题 $total 道" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (week in 0 until weeks) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (day in 0 until 7) {
                    val date = start.plusDays(week * 7L + day)
                    val count = byDay[date] ?: 0
                    val level = when {
                        count >= 20 -> 1f
                        count >= 10 -> 0.8f
                        count >= 4 -> 0.5f
                        count >= 1 -> 0.25f
                        else -> 0f
                    }
                    val future = ChronoUnit.DAYS.between(today, date) > 0
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).background(
                            when {
                                future -> Color.Transparent
                                level == 0f -> colors.text.copy(alpha = 0.07f)
                                else -> colors.accent.copy(alpha = level)
                            },
                            ContinuousShape(3.dp),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccentPicker(current: Accent, onPick: (Accent) -> Unit, modifier: Modifier = Modifier) {
    val colors = Listenly.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Accent.entries.forEach { accent ->
            val selected = accent == current
            val ring by animateColorAsState(if (selected) (if (colors.isDark) accent.dark else accent.light) else Color.Transparent, label = "ring")
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(role = Role.RadioButton) { onPick(accent) }
                    .semantics {
                        contentDescription = accent.label
                        this.selected = selected
                    },
                contentAlignment = Alignment.Center,
            ) {
                val fill = if (accent == Accent.Monet) {
                    Modifier.background(Brush.sweepGradient(listOf(Color(0xFFE5577E), Color(0xFFE8770F), Color(0xFF2F9E6E), Color(0xFF3482FF), Color(0xFF7B61FF), Color(0xFFE5577E))), CircleShape)
                } else {
                    Modifier.background(if (colors.isDark) accent.dark else accent.light, CircleShape)
                }
                Box(
                    Modifier.size(40.dp).border(2.dp, ring, CircleShape).padding(4.dp).then(fill),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(AppIcons.Check, null, tint = if (accent == Accent.Ink && colors.isDark) Color.Black else Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)?) {
    val colors = Listenly.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, color = colors.text, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = colors.textSecondary)
        if (onClick != null) Icon(AppIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.padding(start = 6.dp).size(14.dp))
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(Listenly.colors.hairline))
}
