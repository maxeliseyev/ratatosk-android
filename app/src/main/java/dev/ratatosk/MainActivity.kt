package dev.ratatosk

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ratatosk.ui.RatatoskScreen
import dev.ratatosk.vpn.TunnelController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val consent = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    TunnelController.startNow(this)
                }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> TunnelController.onScreen = true
                        Lifecycle.Event.ON_PAUSE -> TunnelController.onScreen = false
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            RatatoskScreen(
                onConnect = {
                    val prepare = VpnService.prepare(this)
                    if (prepare != null) consent.launch(prepare) else TunnelController.startNow(this)
                },
                onDisconnect = { TunnelController.disconnect() },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** A link opened by the app (`vless://`, `h3://`, `vpn://`) or passed as
     *  an extra by `adb shell am start --es link …`, added to the server list. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.dataString
        val link = when {
            data != null && LINKS.any { data.startsWith(it) } -> data
            else -> intent.getStringExtra("link")
        }
        if (!link.isNullOrEmpty()) TunnelController.accept(link)
    }

    private companion object {
        val LINKS = listOf("vless://", "h3://", "vpn://")
    }
}
