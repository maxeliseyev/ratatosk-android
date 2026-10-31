// JNI surface of the Rust core (libratatosk_android.so, built by
// scripts/update-core.sh from the ratatosk repo). Mirrors the iOS
// RatatoskCore.swift wrapper: the app starts a tunnel on a raw TUN fd and
// asks the core for counters and flows as JSON.

package dev.ratatosk.core

object RatatoskCore {
    init {
        System.loadLibrary("ratatosk_android")
    }

    external fun nativeVersion(): String

    /**
     * Start the core on the raw TUN [tunFd]. Returns a non-zero handle, or
     * throws IllegalStateException (with the core's reason) on failure. The
     * fd is owned by the core from here on and closed by [nativeStop].
     */
    external fun nativeStart(link: String, mtu: Int, directZones: String?, tunFd: Int): Long

    external fun nativeStop(handle: Long)

    /** `[active, total, up, down, errors]`. */
    external fun nativeStats(): LongArray

    /** The newest `max` flows as a JSON array. */
    external fun nativeFlows(handle: Long, max: Int): String?

    external fun nativeNetworkChanged(handle: Long)

    /** JSON array of milliseconds; null where a link is bad or did not answer. */
    external fun nativePing(linksJson: String, timeoutMs: Int): String?

    /** Subscription JSON, or `{"error": "..."}`. */
    external fun nativeFetchSubscription(url: String): String?
}
