package com.voidlink.android.protocol

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Logging for the protocol layer.
 *
 * Two jobs beyond wrapping `android.util.Log`:
 *
 * 1. **Per-subsystem tags** (`VL.Http`, `VL.Pair`, …) so a logcat filter isolates one exchange.
 * 2. **[unverified]** — every code path that depends on a value the spec marks UNVERIFIED calls
 *    it, and it logs at WARN exactly once per process. That turns `docs/01-PROTOCOL.md` §13 from
 *    a list of worries into a checklist that a single run against real hardware ticks off.
 *
 * Never log the private key, the pairing PIN after completion, or a full certificate body.
 */
object ProtocolLog {

    /** Subsystem tags, matching architecture §9. */
    const val TAG_HTTP: String = "VL.Http"
    const val TAG_PAIR: String = "VL.Pair"
    const val TAG_DISCOVERY: String = "VL.Discovery"
    const val TAG_IDENTITY: String = "VL.Identity"
    const val TAG_TLS: String = "VL.Tls"
    const val TAG_WOL: String = "VL.Wol"

    /** Link-quality sampling and the iperf3 client — everything that measures rather than streams. */
    const val TAG_NETPERF: String = "VL.NetPerf"

    private val announcedUnverified = ConcurrentHashMap<String, Boolean>()

    /** Debug-level message. */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    /** Informational message. */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    /** Warning, optionally with a cause. */
    fun w(tag: String, message: String, cause: Throwable? = null) {
        if (cause == null) Log.w(tag, message) else Log.w(tag, message, cause)
    }

    /** Error, optionally with a cause. */
    fun e(tag: String, message: String, cause: Throwable? = null) {
        if (cause == null) Log.e(tag, message) else Log.e(tag, message, cause)
    }

    /**
     * Records that an UNVERIFIED protocol assumption is being relied upon.
     *
     * @param tag subsystem tag.
     * @param key stable identifier of the assumption, used to log it only once per process.
     * @param detail what is being assumed and which spec section flags it.
     */
    fun unverified(tag: String, key: String, detail: String) {
        if (announcedUnverified.putIfAbsent(key, true) == null) {
            Log.w(tag, "UNVERIFIED assumption in use [$key]: $detail")
        }
    }

    /**
     * Clears the once-per-process bookkeeping.
     *
     * Exists so tests can assert on repeat behaviour; production code never calls it.
     */
    fun resetUnverifiedForTesting() {
        announcedUnverified.clear()
    }
}
