// The VpnService: owns the TUN interface, hands its fd to the Rust core and
// keeps the foreground notification alive. The core reads and writes packets
// on the fd itself, so there is no packet pumping here.

package dev.ratatosk.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.VpnService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.ratatosk.MainActivity
import dev.ratatosk.R
import dev.ratatosk.core.RatatoskCore

class RatatoskVpnService : VpnService() {
    private var handle = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivity: ConnectivityManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                val link = intent.getStringExtra(EXTRA_LINK)
                if (link.isNullOrEmpty()) {
                    fail("no link in the start command")
                    return START_NOT_STICKY
                }
                startTunnel(link, intent.getStringExtra(EXTRA_ZONES))
            }

            ACTION_STOP -> stopTunnel()
            else -> {
                // Restarted by the system without a link: nothing to restore.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(link: String, zones: String?) {
        if (handle != 0L) return
        val builder = Builder()
            .setSession("Ratatosk")
            .setMtu(MTU)
            .addAddress(TUNNEL_ADDRESS, 15)
            .addRoute("0.0.0.0", 0)
            .addAddress(TUNNEL_ADDRESS_V6, 64)
            .addRoute("::", 0)
            .addDnsServer(DNS_ADDRESS)
            .setBlocking(true)
            .also {
                // Our own socket to the proxy server must not enter the tunnel.
                runCatching { it.addDisallowedApplication(packageName) }
                    .onFailure { e -> Log.w(TAG, "could not exclude $packageName: ${e.message}") }
            }

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            fail("could not establish the tunnel: ${e.message}")
            return
        }
        if (descriptor == null) {
            fail("the system refused the tunnel (revoked permission?)")
            return
        }

        // The core owns the raw fd from here; detachFd stops the PFD from
        // closing it behind the core's back.
        val fd = descriptor.detachFd()
        val started = try {
            RatatoskCore.nativeStart(link, MTU, zones, fd)
        } catch (e: Exception) {
            fail(e.message ?: "the core refused the link")
            return
        }
        if (started == 0L) {
            fail("the core refused the link")
            return
        }
        handle = started
        watchNetwork()
        TunnelController.onServiceStarted(started)
        Log.i(TAG, "tunnel up (handle $started, fd $fd)")
    }

    /** Let the core follow a Wi-Fi ↔ cellular change. */
    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivity = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (handle != 0L) RatatoskCore.nativeNetworkChanged(handle)
            }
        }
        networkCallback = callback
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }.onFailure { Log.w(TAG, "network callback not registered: ${it.message}") }
    }

    private fun stopTunnel() {
        networkCallback?.let { callback ->
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        connectivity = null
        if (handle != 0L) {
            RatatoskCore.nativeStop(handle)
            handle = 0L
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        TunnelController.onServiceStopped()
        stopSelf()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        TunnelController.onServiceFailed(message)
        stopSelf()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (handle != 0L) {
            RatatoskCore.nativeStop(handle)
            handle = 0L
            TunnelController.onServiceStopped()
        }
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RatatoskVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TunnelController.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Tunnel is up")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.ratatosk.action.START"
        const val ACTION_STOP = "dev.ratatosk.action.STOP"
        const val EXTRA_LINK = "link"
        const val EXTRA_ZONES = "direct_zones"

        private const val TAG = "RatatoskVpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500
        // Must match RATATOSK_TUNNEL_* in the core's ratatosk.h.
        private const val TUNNEL_ADDRESS = "198.18.0.1"
        private const val DNS_ADDRESS = "198.18.0.2"
        private const val TUNNEL_ADDRESS_V6 = "fd52:6174:6174::1"
    }
}
