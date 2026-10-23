package cn.listenly.app.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.listenly.app.ui.glass.ContinuousShape
import cn.listenly.app.data.AppPreferences
import cn.listenly.app.data.SyncToggles
import cn.listenly.app.sync.SyncDevice
import cn.listenly.app.sync.SyncEngine
import cn.listenly.app.sync.SyncException
import cn.listenly.app.sync.SyncStatus
import cn.listenly.app.ui.components.AppIcons
import cn.listenly.app.ui.components.GlassCard
import cn.listenly.app.ui.components.SectionLabel
import cn.listenly.app.ui.glass.Floating
import cn.listenly.app.ui.glass.GlassIconButton
import cn.listenly.app.ui.practice.ActionButton
import cn.listenly.app.ui.theme.Listenly
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SyncScreen(engine: SyncEngine, prefs: AppPreferences, onBack: () -> Unit, onSay: (String) -> Unit) {
    val account by engine.account.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val toggles by prefs.syncToggles.collectAsStateWithLifecycle(SyncToggles(true, true, false))
    val scope = rememberCoroutineScope()
    val colors = Listenly.colors
    var busy by remember { mutableStateOf(false) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (error: SyncException) {
                onSay(error.message ?: "同步失败")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                onSay("操作失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GlassIconButton(AppIcons.Back, "返回", onBack) }
            item { Text("同步与账号", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp)) }

            val signedIn = account
            if (signedIn == null) {
                item { SignInCard(busy, onSubmit = { server, user, pass, register -> run { if (register) engine.register(server, user, pass) else engine.login(server, user, pass) } }) }
                item { PrivacyNote() }
            } else {
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(48.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                                Text(signedIn.username.take(1).uppercase().ifEmpty { "拾" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onAccent)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(signedIn.username, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                                Text(statusText(status), fontSize = 13.sp, color = if (status is SyncStatus.Failed) colors.wrong else colors.textSecondary)
                            }
                        }
                        Text(signedIn.serverUrl, fontSize = 12.sp, color = colors.textTertiary, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                item { SectionLabel("同步内容") }
                item {
                    GlassCard(padding = PaddingValues(vertical = 4.dp)) {
                        ToggleRow("答案与进度", "作答、播放位置、完成记录", toggles.progress) { on ->
                            scope.launch { prefs.setSyncToggles(toggles.copy(progress = on)); if (on) engine.resetCursor(); engine.requestSync() }
                        }
                        ToggleRow("收藏与学习统计", "书签、活跃日历、听力分钟", toggles.stats) { on ->
                            scope.launch { prefs.setSyncToggles(toggles.copy(stats = on)); if (on) engine.resetCursor(); engine.requestSync() }
                        }
                        ToggleRow("外观设置", "强调色、深色模式、倍速", toggles.settings) { on ->
                            scope.launch { prefs.setSyncToggles(toggles.copy(settings = on)); if (on) engine.resetCursor(); engine.requestSync() }
                        }
                    }
                }
                item { DevicesCard(engine) }
                item { PrivacyNote() }
            }
        }
        if (account != null) Floating("sync-actions") {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton("退出登录", primary = false, enabled = !busy, modifier = Modifier.weight(1f)) { run { engine.logout(); onSay("已退出，学习记录仍保留在本机") } }
                ActionButton(if (status is SyncStatus.Syncing) "同步中…" else "立即同步", primary = true, enabled = !busy && status !is SyncStatus.Syncing, modifier = Modifier.weight(1f)) { run { engine.syncNow() } }
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())

private fun statusText(status: SyncStatus): String = when (status) {
    SyncStatus.Syncing -> "正在同步…"
    is SyncStatus.Idle -> status.lastSyncAt?.let { "上次同步：" + timeFormat.format(Instant.ofEpochMilli(it)) } ?: "已登录，尚未同步"
    is SyncStatus.Failed -> status.message
    SyncStatus.SignedOut -> "未登录"
    SyncStatus.Unavailable -> "离线版不包含同步"
}

@Composable
private fun SignInCard(busy: Boolean, onSubmit: (server: String, user: String, pass: String, register: Boolean) -> Unit) {
    val colors = Listenly.colors
    var server by rememberSaveable { mutableStateOf("https://") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    GlassCard {
        Text(if (register) "注册同步账号" else "登录同步账号", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
        Field("服务器地址（可填自建地址）", server, { server = it }, keyboard = KeyboardType.Uri)
        Field("用户名", user, { user = it })
        Field("密码（至少 8 位）", pass, { pass = it }, password = true)
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (register) "改为登录" else "没有账号？注册", primary = false, enabled = !busy, modifier = Modifier.weight(1f)) { register = !register }
            ActionButton(if (busy) "请稍候…" else if (register) "注册并同步" else "登录并同步", primary = true, enabled = !busy && user.isNotBlank() && pass.length >= 8, modifier = Modifier.weight(1f)) {
                onSubmit(server, user, pass, register)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    val colors = Listenly.colors
    Text(label, fontSize = 13.sp, color = colors.textSecondary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (colors.isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.7f), ContinuousShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = Listenly.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = colors.text)
            Text(summary, fontSize = 12.sp, color = colors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DevicesCard(engine: SyncEngine) {
    val colors = Listenly.colors
    var devices by remember { mutableStateOf<List<SyncDevice>?>(null) }
    LaunchedEffect(engine) { devices = runCatching { engine.devices() }.getOrNull() }
    val list = devices ?: return
    SectionLabel("已登录设备 · ${list.size}")
    GlassCard(Modifier.padding(top = 12.dp), padding = PaddingValues(vertical = 4.dp)) {
        list.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(d.name, fontSize = 15.sp, color = colors.text, modifier = Modifier.weight(1f))
                Text(if (d.current) "本机" else "", fontSize = 13.sp, color = colors.accentText)
            }
        }
    }
}

@Composable
private fun PrivacyNote() {
    Text(
        "只上传学习记录（答案、进度、收藏、统计），不上传音频和材料文件。未登录时应用完全离线，不会发出任何网络请求。",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = Listenly.colors.textTertiary,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
