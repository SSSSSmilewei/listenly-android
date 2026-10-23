package cn.listenly.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.listenly.app.ui.glass.ContinuousShape
import cn.listenly.app.AppContainer
import cn.listenly.app.StartupState
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import cn.listenly.app.sync.SyncEngine
import cn.listenly.app.sync.SyncStatus
import cn.listenly.app.ui.components.AppIcons
import cn.listenly.app.ui.glass.FloatingLayer
import cn.listenly.app.ui.glass.IosLiquidGlassNavigationBar
import cn.listenly.app.ui.glass.LocalFloatingHost
import cn.listenly.app.ui.glass.LocalPageBackdrop
import cn.listenly.app.ui.glass.LocalSpecular
import cn.listenly.app.ui.glass.rememberAppSpecular
import cn.listenly.app.ui.glass.liquidGlass
import cn.listenly.app.ui.glass.rememberFloatingHost
import cn.listenly.app.ui.home.HomeScreen
import cn.listenly.app.ui.library.LibraryScreen
import cn.listenly.app.ui.practice.PracticeMode
import cn.listenly.app.ui.practice.PracticeScreen
import cn.listenly.app.ui.practice.PracticeViewModel
import cn.listenly.app.ui.profile.ProfileScreen
import cn.listenly.app.ui.result.ResultScreen
import cn.listenly.app.ui.sync.SyncScreen
import cn.listenly.app.ui.theme.Listenly
import cn.listenly.app.ui.theme.ListenlyTheme
import cn.listenly.app.ui.theme.Wallpaper
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

sealed interface Route {
    data object Tabs : Route
    data class Practice(val paperId: String, val articleId: String, val mode: PracticeMode) : Route
    data class Result(val paperId: String) : Route
    data object Sync : Route
}

@Composable
fun ListenlyApp(container: AppContainer, vm: AppViewModel = viewModel { AppViewModel(container) }) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::importTree) }
    val onImport = { picker.launch(null) }
    val accent by vm.accent.collectAsStateWithLifecycle()
    val darkMode by vm.darkMode.collectAsStateWithLifecycle()
    ListenlyTheme(accent, darkMode) {
        Root(container, vm, onImport)
    }
}

@Composable
private fun Root(container: AppContainer, vm: AppViewModel, onImport: () -> Unit) {
    val colors = Listenly.colors
    val startup by vm.startup.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Route>(Route.Tabs) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var engine by remember { mutableStateOf<SyncEngine?>(null) }
    LaunchedEffect(Unit) { engine = container.sync() }
    val syncStatus by (engine?.status ?: remember { kotlinx.coroutines.flow.MutableStateFlow(SyncStatus.SignedOut) }).collectAsStateWithLifecycle()

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size == 1 && tab != 0) { tab = 0 }

    val shaders = isRuntimeShaderSupported()
    val wallpaperBackdrop = if (shaders) rememberLayerBackdrop() else null
    val pageBackdrop = if (shaders) rememberLayerBackdrop() else null
    val floating = rememberFloatingHost()
    val specular = if (shaders) rememberAppSpecular() else null

    fun open(paper: Paper, article: Article, mode: PracticeMode = PracticeMode.Intensive) {
        stack += Route.Practice(paper.id, article.id, mode)
    }

    CompositionLocalProvider(LocalSpecular provides specular) {
    Box(Modifier.fillMaxSize()) {
        // Everything inside this box is recorded for the floating glass to refract.
        Box(Modifier.fillMaxSize().then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier)) {
            Box(Modifier.fillMaxSize().then(if (wallpaperBackdrop != null) Modifier.layerBackdrop(wallpaperBackdrop) else Modifier)) {
                Wallpaper(colors.isDark)
            }
            CompositionLocalProvider(LocalPageBackdrop provides wallpaperBackdrop, LocalFloatingHost provides floating) {
                when (val s = startup) {
                    StartupState.Loading -> Unit
                    is StartupState.Failed -> Text(
                        "启动失败：${s.message}\n学习记录与材料没有被修改，请重启应用。",
                        color = colors.text,
                        modifier = Modifier.statusBarsPadding().padding(24.dp),
                    )
                    is StartupState.Ready -> AnimatedContent(
                        targetState = stack.last(),
                        transitionSpec = {
                            val forward = stack.size > 1 && targetState != Route.Tabs
                            (slideInHorizontally(tween(320)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220)))
                                .togetherWith(slideOutHorizontally(tween(320)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(180)))
                        },
                        label = "route",
                    ) { route ->
                        Screen(route, container, vm, tab, syncStatus, engine, onImport, ::open, push = { stack += it }, pop = {
                            if (stack.size > 1) stack.removeAt(stack.lastIndex)
                        }, replaceTop = { stack[stack.lastIndex] = it })
                    }
                }
            }
        }
        // Floating Liquid Glass sits above the recorded page and refracts it.
        FloatingLayer(floating, pageBackdrop)
        CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
            AnimatedVisibility(
                visible = startup is StartupState.Ready && stack.last() == Route.Tabs,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                IosLiquidGlassNavigationBar(
                    items = listOf(
                        NavigationItem("听力", AppIcons.Headphones),
                        NavigationItem("资料库", AppIcons.Book),
                        NavigationItem("我的", AppIcons.User),
                    ),
                    selectedIndex = tab,
                    onItemClick = { tab = it },
                    backdrop = pageBackdrop,
                    isBlurActive = pageBackdrop != null,
                )
            }
            MessageToast(vm, Modifier.align(Alignment.TopCenter))
        }
    }
    }
}

@Composable
private fun Screen(
    route: Route,
    container: AppContainer,
    vm: AppViewModel,
    tab: Int,
    syncStatus: SyncStatus,
    engine: SyncEngine?,
    onImport: () -> Unit,
    open: (Paper, Article, PracticeMode) -> Unit,
    push: (Route) -> Unit,
    pop: () -> Unit,
    replaceTop: (Route) -> Unit,
) {
    val papers by vm.papers.collectAsStateWithLifecycle()
    val tabsPadding = PaddingValues(
        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        bottom = 120.dp,
    )
    when (route) {
        Route.Tabs -> AnimatedContent(tab, transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(150))) }, label = "tab") { t ->
            when (t) {
                0 -> HomeScreen(vm, tabsPadding, onImport) { p, a -> open(p, a, PracticeMode.Intensive) }
                1 -> LibraryScreen(vm, tabsPadding, onImport) { p, a -> open(p, a, PracticeMode.Intensive) }
                else -> ProfileScreen(vm, syncStatus, tabsPadding, onImport) { push(Route.Sync) }
            }
        }
        is Route.Practice -> {
            val paper = papers.firstOrNull { it.id == route.paperId } ?: return
            val practice: PracticeViewModel = viewModel(key = "practice-${paper.id}") { PracticeViewModel(container, paper.id) }
            PracticeScreen(
                vm = vm,
                practice = practice,
                paper = paper,
                initialArticleId = route.articleId,
                initialMode = route.mode,
                onBack = pop,
                onFinished = { replaceTop(Route.Result(paper.id)) },
            )
        }
        is Route.Result -> {
            val paper = papers.firstOrNull { it.id == route.paperId } ?: return
            val practice: PracticeViewModel = viewModel(key = "practice-${paper.id}") { PracticeViewModel(container, paper.id) }
            ResultScreen(
                practice = practice,
                paper = paper,
                onClose = pop,
                onRedo = { replaceTop(Route.Practice(paper.id, paper.articles.first().id, PracticeMode.Exam)) },
                onReview = { article -> replaceTop(Route.Practice(paper.id, article.id, PracticeMode.Intensive)) },
            )
        }
        Route.Sync -> {
            val e = engine ?: return
            SyncScreen(e, container.prefs, onBack = pop, onSay = vm::say)
        }
    }
}

/** Floating glass toast for short confirmations and errors. */
@Composable
private fun MessageToast(vm: AppViewModel, modifier: Modifier = Modifier) {
    val message by vm.message.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val text = when (val s = importState) {
        ImportState.Idle -> message?.text
        is ImportState.Copying -> "正在复制材料… ${s.files} 个文件 · ${s.megabytes} MB"
        ImportState.Validating -> "正在核对音频与时间标记…"
    }
    LaunchedEffect(message?.id) {
        val id = message?.id ?: return@LaunchedEffect
        delay(2_800)
        vm.clearMessage(id)
    }
    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier.statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp),
    ) {
        Box(Modifier.liquidGlass(ContinuousShape(22.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(text.orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Listenly.colors.text)
        }
    }
}
