// Servers and subscriptions persisted in EncryptedSharedPreferences: links
// and subscription URLs carry account secrets, so they must not sit in plain
// SharedPreferences (the iOS app uses the Keychain instead).

package dev.ratatosk.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.ratatosk.core.SubscriptionData
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ServerStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ratatosk_servers",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    val servers = mutableListOf<Server>()
    val subscriptions = mutableListOf<SubscriptionEntry>()
    var activeId: String? = null
        private set

    val active: Server?
        get() = servers.firstOrNull { it.id == activeId } ?: servers.firstOrNull()

    init {
        load()
    }

    /** Add `link` by hand (or find it) and make it active. */
    fun add(link: String): Server {
        val server = servers.firstOrNull { it.link == link } ?: Server(UUID.randomUUID().toString(), link).also {
            servers += it
        }
        activeId = server.id
        save()
        return server
    }

    fun select(id: String) {
        if (servers.none { it.id == id }) return
        activeId = id
        save()
    }

    fun remove(id: String) {
        servers.removeAll { it.id == id }
        if (activeId == id) activeId = servers.firstOrNull()?.id
        save()
    }

    fun addSubscription(url: String): SubscriptionEntry {
        subscriptions.firstOrNull { it.url == url }?.let { return it }
        val entry = SubscriptionEntry(UUID.randomUUID().toString(), url)
        subscriptions += entry
        save()
        return entry
    }

    /** Replace subscription `id`'s servers with a fresh download. */
    fun apply(fetched: SubscriptionData, id: String) {
        val index = subscriptions.indexOfFirst { it.id == id }
        if (index < 0) return
        val sub = subscriptions[index]
        fetched.title?.let { sub.title = it }
        sub.updated = System.currentTimeMillis()
        sub.upload = fetched.upload
        sub.download = fetched.download
        sub.total = fetched.total
        sub.expire = fetched.expire?.takeIf { it > 0 }
        sub.intervalHours = fetched.intervalHours.coerceAtLeast(1)

        val old = servers.filter { it.subscription == id }
        val fresh = fetched.links.map { link ->
            old.firstOrNull { it.link == link } ?: Server(UUID.randomUUID().toString(), link, id)
        }
        val at = servers.indexOfFirst { it.subscription == id }.takeIf { it >= 0 } ?: servers.size
        servers.removeAll { it.subscription == id }
        servers.addAll(at.coerceAtMost(servers.size), fresh)
        if (servers.none { it.id == activeId }) {
            activeId = fresh.firstOrNull()?.id ?: servers.firstOrNull()?.id
        }
        save()
    }

    fun removeSubscription(id: String) {
        subscriptions.removeAll { it.id == id }
        servers.removeAll { it.subscription == id }
        if (servers.none { it.id == activeId }) activeId = servers.firstOrNull()?.id
        save()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            root.optJSONArray("servers")?.let { array ->
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    servers += Server(
                        id = o.getString("id"),
                        link = o.getString("link"),
                        subscription = o.optNullable("subscription"),
                    )
                }
            }
            root.optJSONArray("subscriptions")?.let { array ->
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    subscriptions += SubscriptionEntry(
                        id = o.getString("id"),
                        url = o.getString("url"),
                        title = o.optNullable("title"),
                        updated = o.optNullableLong("updated"),
                        upload = o.optNullableLong("upload"),
                        download = o.optNullableLong("download"),
                        total = o.optNullableLong("total"),
                        expire = o.optNullableLong("expire"),
                        intervalHours = o.optLong("interval_hours", 24),
                    )
                }
            }
            activeId = root.optNullable("active")
        }
    }

    private fun save() {
        val root = JSONObject()
        root.put("servers", JSONArray().apply {
            servers.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("link", it.link)
                    it.subscription?.let { sub -> put("subscription", sub) }
                })
            }
        })
        root.put("subscriptions", JSONArray().apply {
            subscriptions.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("url", it.url)
                    it.title?.let { title -> put("title", title) }
                    it.updated?.let { v -> put("updated", v) }
                    it.upload?.let { v -> put("upload", v) }
                    it.download?.let { v -> put("download", v) }
                    it.total?.let { v -> put("total", v) }
                    it.expire?.let { v -> put("expire", v) }
                    put("interval_hours", it.intervalHours)
                })
            }
        })
        activeId?.let { root.put("active", it) }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}

private fun JSONObject.optNullable(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key)
