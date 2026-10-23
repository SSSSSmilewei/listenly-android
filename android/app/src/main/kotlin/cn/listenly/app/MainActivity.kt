package cn.listenly.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.listenly.app.ui.ListenlyApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ListenlyApp).container
        setContent { ListenlyApp(container) }
    }

    override fun onStop() {
        super.onStop()
        val container = (application as ListenlyApp).container
        container.player.checkpointNow()
    }
}
