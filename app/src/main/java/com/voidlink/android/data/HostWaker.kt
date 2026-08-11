package com.voidlink.android.data

/**
 * Sends Wake-on-LAN magic packets.
 *
 * Split out from [HostStatusProvider] because waking a host is a fire-and-forget UDP broadcast with
 * no reply, whereas probing is a request/response exchange — and because the UI needs to grey the
 * Wake button out when a host has no MAC on record, which it can decide from [KnownHost] alone.
 *
 * The protocol layer supplies the real implementation later; [StubHostWaker] keeps the UI honest
 * until then by reporting that the packet could not be sent.
 */
interface HostWaker {
    /**
     * Attempts to wake [host].
     *
     * @return true when a magic packet was actually broadcast.
     */
    suspend fun wake(host: KnownHost): Boolean
}

/** No-op [HostWaker] used before the protocol layer exists. */
object StubHostWaker : HostWaker {
    override suspend fun wake(host: KnownHost): Boolean = false
}
