package cn.listenly.app.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * Floating controls must refract the page scrolling beneath them, so they cannot live inside the
 * layer the page is recorded into (they would sample themselves). Screens declare them with
 * [Floating]; the root draws them above the recorded page with the full page as their backdrop.
 */
class FloatingHost {
    /** Keyed by call-site instance, so an outgoing and an incoming screen never evict each other. */
    internal val entries = mutableStateMapOf<Any, @Composable BoxScope.() -> Unit>()
}

val LocalFloatingHost = staticCompositionLocalOf { FloatingHost() }

@Composable
fun Floating(@Suppress("UNUSED_PARAMETER") name: String, content: @Composable BoxScope.() -> Unit) {
    val host = LocalFloatingHost.current
    val token = remember { Any() }
    SideEffect { host.entries[token] = content }
    DisposableEffect(host, token) { onDispose { host.entries.remove(token) } }
}

@Composable
fun FloatingLayer(host: FloatingHost, pageBackdrop: LayerBackdrop?, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
        Box(modifier.fillMaxSize()) {
            for ((token, entry) in host.entries) key(token) { entry() }
        }
    }
}

@Composable
fun rememberFloatingHost(): FloatingHost = remember { FloatingHost() }
