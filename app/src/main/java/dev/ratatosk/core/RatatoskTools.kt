// Core calls that need no running tunnel: server ping and subscription
// download. Mirrors RatatoskTools.swift on iOS. Both call into Rust and block,
// so run them off the main thread.

package dev.ratatosk.core

import org.json.JSONArray
import org.json.JSONObject

data class TrafficStats(
    val active: Long,
    val total: Long,
    val up: Long,
    val down: Long,
    val errors: Long,
)

/** One connection or UDP flow from the core's log (`nativeFlows`). */
data class FlowRow(
    val id: Long,
    val t: Long,
    val proto: String,
    val host: String,
    val port: Int,
    val route: String,
    val up: Long,
    val down: Long,
    val open: Boolean,
    val failed: Boolean,
)

/** What a subscription URL serves. */
data class SubscriptionData(
    val title: String?,
    val links: List<String>,
    val skipped: Int,
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    /** Unix seconds; 0 or null for none. */
    val expire: Long?,
    val intervalHours: Long,
)

object RatatoskTools {
    fun version(): String = RatatoskCore.nativeVersion()

    /** Handshake time per link in milliseconds, null where it failed. */
    fun ping(links: List<String>, timeoutMs: Int = 3000): List<Int?> {
        if (links.isEmpty()) return emptyList()
        val input = JSONArray(links as List<*>).toString()
        val out = RatatoskCore.nativePing(input, timeoutMs) ?: return List(links.size) { null }
        val array = JSONArray(out)
        return (0 until links.size).map { i ->
            if (i >= array.length() || array.isNull(i)) null else array.getInt(i)
        }
    }

    fun fetchSubscription(url: String): Result<SubscriptionData> {
        val out = RatatoskCore.nativeFetchSubscription(url)
            ?: return Result.failure(IllegalStateException("no answer from the core"))
        val json = JSONObject(out)
        if (json.has("error")) {
            return Result.failure(IllegalStateException(json.getString("error")))
        }
        val linksJson = json.optJSONArray("links") ?: JSONArray()
        val links = (0 until linksJson.length()).map { linksJson.getString(it) }
        return Result.success(
            SubscriptionData(
                title = json.optNullableString("title"),
                links = links,
                skipped = json.optInt("skipped"),
                upload = json.optNullableLong("upload"),
                download = json.optNullableLong("download"),
                total = json.optNullableLong("total"),
                expire = json.optNullableLong("expire"),
                intervalHours = json.optLong("interval_hours", 24),
            )
        )
    }

    fun parseStats(values: LongArray?): TrafficStats? {
        if (values == null || values.size < 5) return null
        return TrafficStats(values[0], values[1], values[2], values[3], values[4])
    }

    fun parseFlows(json: String?): List<FlowRow> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            FlowRow(
                id = o.getLong("id"),
                t = o.getLong("t"),
                proto = o.getString("proto"),
                host = o.getString("host"),
                port = o.getInt("port"),
                route = o.getString("route"),
                up = o.getLong("up"),
                down = o.getLong("down"),
                open = o.getBoolean("open"),
                failed = o.optBoolean("failed"),
            )
        }
    }

    /** `1.2 MB`; matches ContentView.human on iOS. */
    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return "%.1f %s".format(value, units[i])
    }
}

fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return getString(key).ifEmpty { null }
}

fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return getLong(key)
}
