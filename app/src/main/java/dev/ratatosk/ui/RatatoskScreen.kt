// The single screen, in the look of the approved mockup: monochrome,
// monospaced, status block, traffic, then the live flow log while connected
// (the server list while not) and one connect control. Mirrors ContentView.swift.

package dev.ratatosk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ratatosk.core.FlowRow
import dev.ratatosk.core.RatatoskTools
import dev.ratatosk.data.Server
import dev.ratatosk.data.SubscriptionEntry
import dev.ratatosk.vpn.Ping
import dev.ratatosk.vpn.TunnelController
import dev.ratatosk.vpn.VpnStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Palette {
    val ground = Color(0xFF111111)
    val panel = Color(0xFF1A1A1A)
    val line = Color(0xFF262626)
    val border = Color(0xFF333333)
    val text = Color(0xFFE6E6E6)
    val dim = Color(0xFF9A9A9A)
    val direct = Color(0xFFB5B5B5)
    val block = Color(0xFF8A8A8A)
}

private val mono = FontFamily.Monospace

@Composable
fun RatatoskScreen(onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val status by TunnelController.status.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Palette.ground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(status)
            StatusBlock(status)
            Traffic()
            if (isActive(status)) {
                FlowLog(Modifier.weight(1f))
            } else {
                SplitTunnelToggle()
                ServerList(
                    onScan = { scanning = true },
                    modifier = Modifier.weight(1f),
                )
                ErrorLine()
            }
            ConnectButton(status, onConnect, onDisconnect)
        }
    }

    if (scanning) {
        QrScannerDialog(
            onResult = {
                scanning = false
                TunnelController.accept(it)
            },
            onDismiss = { scanning = false },
        )
    }
}

@Composable
private fun Header(status: VpnStatus) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "~/ratatosk",
            color = Palette.text,
            fontFamily = mono,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Text(stateWord(status), color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
    }
}

@Composable
private fun StatusBlock(status: VpnStatus) {
    val stats by TunnelController.stats.collectAsStateWithLifecycle()
    val active by TunnelController.activeServer.collectAsStateWithLifecycle()
    val now by rememberTicker()

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusRow("server") {
            Text(
                active?.name ?: "—",
                color = Palette.text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        StatusRow("status") { Text(statusText(status), color = Palette.text, maxLines = 1) }
        StatusRow("uptime") { Text(uptime(now), color = Palette.text, maxLines = 1) }
        StatusRow("flows") {
            val value = stats?.let { "${it.active} open · ${it.total} total" } ?: "—"
            Text(value, color = Palette.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: @Composable () -> Unit) {
    Row {
        Box(Modifier.width(88.dp)) {
            Text(label, color = Palette.dim)
        }
        value()
    }
}

@Composable
private fun Traffic() {
    val stats by TunnelController.stats.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TrafficCard("↑ up", stats?.up, Modifier.weight(1f))
        TrafficCard("↓ down", stats?.down, Modifier.weight(1f))
    }
}

@Composable
private fun TrafficCard(title: String, bytes: Long?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.panel)
            .border(1.dp, Palette.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Palette.dim, fontFamily = mono, fontSize = 11.sp)
        Text(
            bytes?.let { RatatoskTools.human(it) } ?: "0 B",
            color = Palette.text,
            fontFamily = mono,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FlowLog(modifier: Modifier = Modifier) {
    val flows by TunnelController.flows.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("── tail -f log", color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (flows.isNotEmpty()) {
                Text("${flows.size} flows", color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
            }
        }
        if (flows.isEmpty()) {
            Text("waiting for traffic…", color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(flows, key = { it.id }) { flow -> FlowLine(flow) }
            }
        }
    }
}

@Composable
private fun FlowLine(flow: FlowRow) {
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val routeColor = when (flow.route) {
        "proxy" -> Palette.text
        "direct" -> Palette.direct
        else -> Palette.block
    }
    val target = if (flow.proto == "udp") "${flow.host} udp:${flow.port}" else "${flow.host}:${flow.port}"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time.format(Date(flow.t)), color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
        Text(
            flow.route,
            color = routeColor,
            fontFamily = mono,
            fontSize = 12.sp,
            fontWeight = if (flow.route == "proxy") FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            target,
            color = if (flow.open) Palette.text else Palette.dim,
            fontFamily = mono,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        val total = flow.up + flow.down
        if (total > 0) {
            Text(RatatoskTools.human(total), color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SplitTunnelToggle() {
    val enabled by TunnelController.splitTunnelRU.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().clickable { TunnelController.setSplitTunnelRU(!enabled) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (enabled) "[x]" else "[ ]", color = Palette.text, fontFamily = mono)
        Column {
            Text("RU direct", color = Palette.text, fontFamily = mono)
            Text(
                ".ru .su .рф bypass the proxy",
                color = Palette.dim,
                fontFamily = mono,
                fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerList(onScan: () -> Unit, modifier: Modifier = Modifier) {
    val subscriptions by TunnelController.subscriptions.collectAsStateWithLifecycle()
    val servers by TunnelController.servers.collectAsStateWithLifecycle()
    val pings by TunnelController.pings.collectAsStateWithLifecycle()
    val refreshing by TunnelController.refreshing.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        subscriptions.forEach { sub ->
            SubscriptionCard(sub, busy = sub.id in refreshing)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$ ls -l servers", color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (servers.isNotEmpty()) {
                val busy = pings.values.any { it is Ping.Pending }
                TextButton(onClick = { TunnelController.pingAll() }) {
                    Text(if (busy) "ping…" else "ping all", color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
                }
                TextButton(onClick = { TunnelController.selectBest() }) {
                    Text("best", color = Palette.dim, fontFamily = mono, fontSize = 12.sp)
                }
            }
        }
        if (servers.isEmpty()) {
            Text(
                "no servers yet: paste a vless://, h3:// or vpn:// link or a subscription URL, or scan a QR",
                color = Palette.dim,
                fontFamily = mono,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(servers, key = { it.id }) { server ->
                    ServerItem(server, pings[server.id])
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton(
                label = "paste",
                modifier = Modifier.weight(1f),
                onClick = {
                    clipboard.getText()?.text?.let { TunnelController.accept(it) }
                },
            )
            TerminalButton(label = "scan QR", modifier = Modifier.weight(1f), onClick = onScan)
        }
    }
}

@Composable
private fun TerminalButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.panel)
            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Palette.text, fontFamily = mono, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerItem(server: Server, ping: Ping?) {
    val activeServer by TunnelController.activeServer.collectAsStateWithLifecycle()
    val active = activeServer?.id == server.id
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (active) Palette.panel else Color.Transparent)
                .border(
                    1.dp,
                    if (active) Palette.border else Color.Transparent,
                    RoundedCornerShape(10.dp),
                )
                .combinedClickable(
                    onClick = { TunnelController.select(server.id) },
                    onLongClick = { menu = true },
                )
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (active) "●" else " ", color = Palette.text, fontFamily = mono)
            Column(Modifier.weight(1f)) {
                Text(
                    server.name,
                    color = Palette.text,
                    fontFamily = mono,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    server.kind,
                    color = Palette.dim,
                    fontFamily = mono,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ping != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(bars(ping), color = if (fast(ping)) Palette.text else Palette.dim, fontFamily = mono)
                    Text(label(ping), color = Palette.dim, fontFamily = mono, fontSize = 11.sp)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("copy link") }, onClick = {
                clipboard.setText(AnnotatedString(server.link))
                menu = false
            })
            DropdownMenuItem(text = { Text("remove") }, onClick = {
                TunnelController.removeServer(server.id)
                menu = false
            })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionCard(sub: SubscriptionEntry, busy: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.panel)
                .border(1.dp, Palette.line, RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = { TunnelController.refresh(sub.id) },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sub.name,
                    color = Palette.text,
                    fontFamily = mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(if (busy) "updating…" else updated(sub), color = Palette.dim, fontFamily = mono, fontSize = 11.sp)
            }
            trafficBar(sub)?.let { (bar, text, until) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bar.isNotEmpty()) Text(bar, color = Palette.text, fontFamily = mono, fontSize = 11.sp)
                    Text(text, color = Palette.dim, fontFamily = mono, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    until?.let { Text(it, color = Palette.dim, fontFamily = mono, fontSize = 11.sp) }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("refresh") }, onClick = {
                TunnelController.refresh(sub.id)
                menu = false
            })
            DropdownMenuItem(text = { Text("copy URL") }, onClick = {
                clipboard.setText(AnnotatedString(sub.url))
                menu = false
            })
            DropdownMenuItem(text = { Text("remove with its servers") }, onClick = {
                TunnelController.removeSubscription(sub.id)
                menu = false
            })
        }
    }
}

@Composable
private fun ConnectButton(status: VpnStatus, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val active = isActive(status)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Palette.panel else Palette.text)
            .border(1.dp, if (active) Palette.border else Palette.text, RoundedCornerShape(12.dp))
            .clickable { if (active) onDisconnect() else onConnect() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            buttonLabel(status),
            color = if (active) Palette.text else Palette.ground,
            fontFamily = mono,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ErrorLine() {
    val error by TunnelController.error.collectAsStateWithLifecycle()
    error?.let { Text(it, color = Palette.dim, fontFamily = mono, fontSize = 12.sp) }
}

// ── helpers ────────────────────────────────────────────────────────────────

private fun isActive(status: VpnStatus): Boolean =
    status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING || status == VpnStatus.DISCONNECTING

private fun stateWord(status: VpnStatus): String = when (status) {
    VpnStatus.CONNECTED -> "connected"
    VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> "dialing"
    VpnStatus.DISCONNECTED -> "off"
}

private fun statusText(status: VpnStatus): String = when (status) {
    VpnStatus.CONNECTED -> "connected"
    VpnStatus.CONNECTING -> "connecting"
    VpnStatus.DISCONNECTING -> "disconnecting"
    VpnStatus.DISCONNECTED -> "disconnected"
}

private fun buttonLabel(status: VpnStatus): String = when (status) {
    VpnStatus.CONNECTED -> "disconnect"
    VpnStatus.CONNECTING, VpnStatus.DISCONNECTING -> "… cancel"
    VpnStatus.DISCONNECTED -> "connect"
}

private fun bars(ping: Ping): String = when (ping) {
    Ping.Pending -> "░░░░"
    Ping.Timeout -> "····"
    is Ping.Ms -> when {
        ping.ms < 60 -> "▂▄▆█"
        ping.ms < 150 -> "▂▄▆·"
        ping.ms < 300 -> "▂▄··"
        else -> "▂···"
    }
}

private fun label(ping: Ping): String = when (ping) {
    Ping.Pending -> "…"
    Ping.Timeout -> "timeout"
    is Ping.Ms -> "${ping.ms} ms"
}

private fun fast(ping: Ping): Boolean = ping is Ping.Ms && ping.ms < 150

private fun updated(sub: SubscriptionEntry): String {
    val at = sub.updated ?: return "not loaded"
    val minutes = ((System.currentTimeMillis() - at) / 60_000).toInt()
    return when {
        minutes < 1 -> "updated now"
        minutes < 60 -> "updated ${minutes}m ago"
        minutes < 48 * 60 -> "updated ${minutes / 60}h ago"
        else -> "updated ${minutes / 1440}d ago"
    }
}

private fun trafficBar(sub: SubscriptionEntry): Triple<String, String, String?>? {
    val used = (sub.upload ?: 0) + (sub.download ?: 0)
    if (used <= 0 && sub.total == null) return null
    val gb = { bytes: Long ->
        val value = bytes.toDouble() / 1_073_741_824
        if (value >= 10) "%.0f".format(value) else "%.1f".format(value)
    }
    val total = sub.total
    val until = sub.expire?.let { "until ${SimpleDateFormat("d MMM", Locale.US).format(Date(it * 1000))}" }
    if (total == null || total <= 0) {
        return Triple("", "${gb(used)} GB used", until)
    }
    val filled = ((used.toDouble() / total) * 16).toInt().coerceIn(0, 16)
    val bar = "█".repeat(filled) + "░".repeat(16 - filled)
    return Triple(bar, "${gb(used)} / ${gb(total)} GB", until)
}

@Composable
private fun rememberTicker(): androidx.compose.runtime.State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return state
}

private fun uptime(now: Long): String {
    val since = TunnelController.connectedSince ?: return "—"
    val seconds = ((now - since) / 1000).toInt()
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
