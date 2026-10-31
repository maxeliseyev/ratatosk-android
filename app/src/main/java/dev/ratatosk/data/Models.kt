// The user's servers and subscriptions, mirroring ServerStore.swift on iOS.

package dev.ratatosk.data

import android.net.Uri

/** One server: a vless://, h3:// or vpn:// link, added by hand or from a subscription. */
data class Server(
    val id: String,
    val link: String,
    /** The subscription it came from; refreshing it replaces these. */
    val subscription: String? = null,
) {
    /** The link's `#name`, else its host. */
    val name: String
        get() {
            if (link.startsWith("vpn://")) return "amnezia"
            val uri = Uri.parse(link)
            uri.fragment?.takeIf { it.isNotEmpty() }?.let { return it }
            return uri.host ?: "server"
        }

    /** `reality · vision · tcp`, `tls · ws`, `h3 · quic`, `amnezia (vpn://)`. */
    val kind: String
        get() {
            if (link.startsWith("vpn://")) return "amnezia (vpn://)"
            if (link.startsWith("h3://")) return "h3 · quic"
            val uri = Uri.parse(link)
            val parts = mutableListOf(
                uri.getQueryParameter("security")?.takeIf { it.isNotEmpty() } ?: "none"
            )
            if (uri.getQueryParameter("flow")?.contains("vision") == true) parts += "vision"
            parts += uri.getQueryParameter("type")?.takeIf { it.isNotEmpty() } ?: "tcp"
            return parts.joinToString(" · ")
        }
}

/** A subscription URL from a panel and what it said last time. */
data class SubscriptionEntry(
    val id: String,
    val url: String,
    var title: String? = null,
    /** Last successful refresh, unix ms. */
    var updated: Long? = null,
    var upload: Long? = null,
    var download: Long? = null,
    var total: Long? = null,
    /** Unix seconds. */
    var expire: Long? = null,
    var intervalHours: Long = 24,
) {
    val name: String
        get() = title ?: Uri.parse(url).host ?: "subscription"

    val isStale: Boolean
        get() {
            val at = updated ?: return true
            return System.currentTimeMillis() - at > intervalHours * 3_600_000
        }
}
