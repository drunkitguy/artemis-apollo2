package com.voidlink.android.protocol.bridge

import com.voidlink.android.data.HostWaker
import com.voidlink.android.data.KnownHost
import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.wol.WakeOnLan

/**
 * The real [HostWaker]: broadcasts a magic packet for a saved host (spec §1.4).
 *
 * A host only has a MAC on record if we learned one — over HTTPS from a paired Sunshine, or from
 * a GFE host that publishes it in plaintext. Without one this reports failure rather than
 * pretending, which is what lets the Hosts screen tell the user why the button did nothing.
 */
class WakeOnLanHostWaker : HostWaker {

    override suspend fun wake(host: KnownHost): Boolean = WakeOnLan.wake(
        macText = host.macAddress,
        // The bare host, without the port suffix the address string may carry.
        lastKnownAddress = HostAddress.parse(host.primaryAddress)?.host,
    )
}
