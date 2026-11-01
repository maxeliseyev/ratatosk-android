// The app's side of the tunnel, mirroring TunnelController.swift on iOS.
// Android runs the VpnService in the same process, so state is shared
// directly instead of through provider messages.

package dev.ratatosk.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.ratatosk.core.FlowRow
import dev.ratatosk.core.RatatoskCore
import dev.ratatosk.core.RatatoskTools
import dev.ratatosk.core.TrafficStats
import dev.ratatosk.data.Server
import dev.ratatosk.data.ServerStore
import dev.ratatosk.data.SubscriptionEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

sealed interface Ping {
    data object Pending : Ping
    data class Ms(val ms: Int) : Ping
    data object Timeout : Ping
}

object TunnelController {
    const val CHANNEL_ID = "ratatosk-tunnel"

    /** Domain suffixes routed direct by the split-tunnel toggle. */
    private const val RU_ZONE = "ru,su,рф"

    lateinit var store: ServerStore
        private set

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()
    private val _stats = MutableStateFlow<TrafficStats?>(null)
    val stats: StateFlow<TrafficStats?> = _stats.asStateFlow()
    private val _flows = MutableStateFlow<List<FlowRow>>(emptyList())
    val flows: StateFlow<List<FlowRow>> = _flows.asStateFlow()
    private val _pings = MutableStateFlow<Map<String, Ping>>(emptyMap())
    val pings: StateFlow<Map<String, Ping>> = _pings.asStateFlow()
    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _splitTunnelRU = MutableStateFlow(false)
    val splitTunnelRU: StateFlow<Boolean> = _splitTunnelRU.asStateFlow()

    // A Compose-observable mirror of ServerStore: the store itself is plain
    // mutable state written from IO threads, so the UI reads these flows.
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()
    private val _subscriptions = MutableStateFlow<List<SubscriptionEntry>>(emptyList())
    val subscriptions: StateFlow<List<SubscriptionEntry>> = _subscriptions.asStateFlow()
    private val _activeServer = MutableStateFlow<Server?>(null)
    val activeServer: StateFlow<Server?> = _activeServer.asStateFlow()

    var connectedSince: Long? = null
        private set

    /** Whether the app is in front; stats and flows are polled only then. */
    @Volatile
    var onScreen: Boolean = true

    private var handle = 0L
    private var reconnect = false

    fun init(context: Context) {
        appContext = context.applicationContext
        store = ServerStore(appContext)
        syncStore()
        _splitTunnelRU.value = appContext
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("splitTunnelRU", false)
        scope.launch { poll() }
        store.subscriptions.filter { it.isStale }.forEach { refresh(it.id) }
    }

    fun setSplitTunnelRU(enabled: Boolean) {
        if (_splitTunnelRU.value == enabled) return
        _splitTunnelRU.value = enabled
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("splitTunnelRU", enabled).apply()
        if (isRunning) {
            reconnect = true
            disconnect()
        }
    }

    /** Start the VpnService for the active server. Consent is asked by the UI. */
    fun startNow(context: Context) {
        val link = store.active?.link
        if (link == null) {
            _error.value = "add a server: paste a link or scan a QR"
            return
        }
        _error.value = null
        _status.value = VpnStatus.CONNECTING
        val intent = Intent(context, RatatoskVpnService::class.java).apply {
            action = RatatoskVpnService.ACTION_START
            putExtra(RatatoskVpnService.EXTRA_LINK, link)
            if (_splitTunnelRU.value) putExtra(RatatoskVpnService.EXTRA_ZONES, RU_ZONE)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun disconnect() {
        _status.value = VpnStatus.DISCONNECTING
        appContext.startService(
            Intent(appContext, RatatoskVpnService::class.java).apply {
                action = RatatoskVpnService.ACTION_STOP
            }
        )
    }

    /** Called by the service once the core is up. */
    fun onServiceStarted(nativeHandle: Long) {
        handle = nativeHandle
        connectedSince = System.currentTimeMillis()
        _error.value = null
        _status.value = VpnStatus.CONNECTED
    }

    fun onServiceStopped() {
        handle = 0L
        connectedSince = null
        _status.value = VpnStatus.DISCONNECTED
        _stats.value = null
        if (reconnect) {
            reconnect = false
            startNow(appContext)
        }
    }

    fun onServiceFailed(message: String) {
        handle = 0L
        connectedSince = null
        _status.value = VpnStatus.DISCONNECTED
        _error.value = message
    }

    /** Make `id` the active server; a running tunnel reconnects to it. */
    fun select(id: String) {
        if (id == store.active?.id) return
        store.select(id)
        syncStore()
        if (isRunning) {
            reconnect = true
            disconnect()
        }
    }

    fun selectBest() {
        val best = _pings.value.entries
            .filter { it.value is Ping.Ms }
            .minByOrNull { (it.value as Ping.Ms).ms }?.key ?: return
        select(best)
    }

    /** A pasted or scanned link: a server link, or a subscription URL. */
    fun accept(text: String) {
        val value = text.trim()
        if (value.startsWith("https://") || value.startsWith("http://")) {
            _error.value = null
            val sub = store.addSubscription(value)
            refresh(sub.id)
            return
        }
        if (!isLink(value)) {
            _error.value = "not a vless://, h3://, vpn:// link or a subscription URL"
            return
        }
        _error.value = null
        store.add(value)
        syncStore()
    }

    fun removeServer(id: String) {
        store.remove(id)
        syncStore()
    }

    fun removeSubscription(id: String) {
        store.removeSubscription(id)
        syncStore()
    }

    fun refresh(id: String) {
        val sub = store.subscriptions.firstOrNull { it.id == id } ?: return
        if (id in _refreshing.value) return
        _refreshing.value = _refreshing.value + id
        scope.launch {
            RatatoskTools.fetchSubscription(sub.url)
                .onSuccess {
                    store.apply(it, id)
                    syncStore()
                }
                .onFailure { _error.value = "${sub.name}: ${it.message}" }
            _refreshing.value = _refreshing.value - id
        }
    }

    /** Time every server's handshake, all at once. */
    fun pingAll() {
        val list = store.servers.toList()
        if (list.isEmpty() || _pings.value.values.any { it is Ping.Pending }) return
        _pings.value = list.associate { it.id to Ping.Pending }
        scope.launch {
            val measured = RatatoskTools.ping(list.map { it.link })
            _pings.value = list.mapIndexed { i, server ->
                server.id to (measured.getOrNull(i)?.let { Ping.Ms(it) } ?: Ping.Timeout)
            }.toMap()
        }
    }

    val serverName: String
        get() = store.active?.name ?: "—"

    val isRunning: Boolean
        get() = _status.value == VpnStatus.CONNECTED ||
            _status.value == VpnStatus.CONNECTING ||
            _status.value == VpnStatus.DISCONNECTING

    private suspend fun poll() {
        // Poll fast while traffic moves and slower while it is quiet: the
        // byte counters are the liveness signal, so an idle tunnel does not
        // wake the UI every second.
        var lastUp = -1L
        var lastDown = -1L
        while (currentCoroutineContext().isActive) {
            if (onScreen && _status.value == VpnStatus.CONNECTED && handle != 0L) {
                val stats = RatatoskTools.parseStats(RatatoskCore.nativeStats())
                _stats.value = stats
                _flows.value = RatatoskTools.parseFlows(RatatoskCore.nativeFlows(handle, 100))
                val moved = stats != null && (stats.up != lastUp || stats.down != lastDown)
                if (stats != null) {
                    lastUp = stats.up
                    lastDown = stats.down
                }
                delay(if (moved) 1_000 else 3_000)
            } else {
                lastUp = -1L
                lastDown = -1L
                delay(1_000)
            }
        }
    }

    private fun syncStore() {
        _servers.value = store.servers.toList()
        _subscriptions.value = store.subscriptions.toList()
        _activeServer.value = store.active
    }

    private fun isLink(text: String): Boolean =
        listOf("vless://", "h3://", "vpn://").any { text.startsWith(it) }
}
