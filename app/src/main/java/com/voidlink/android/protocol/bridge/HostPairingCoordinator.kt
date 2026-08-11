package com.voidlink.android.protocol.bridge

import com.voidlink.android.data.KnownHost
import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.pairing.PairProgress
import com.voidlink.android.protocol.pairing.PairResult
import com.voidlink.android.protocol.pairing.PairingEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Pairing, expressed in terms of the saved-host model the UI uses.
 *
 * [PairingEngine] deliberately knows nothing about [KnownHost] — it takes an address and a
 * `/serverinfo` result, which is all the protocol needs. This adapter is the join: it finds the
 * host's reachable address, fetches the `/serverinfo` the handshake needs for its hash selection
 * and HTTPS port, and then hands over.
 *
 * @param resolver picks the reachable address.
 * @param engine the five-phase handshake.
 */
class HostPairingCoordinator(
    private val resolver: HostEndpointResolver,
    private val engine: PairingEngine,
) {

    /**
     * Pairs with [host], emitting the PIN and then per-phase progress.
     *
     * Cold and cancellable: cancelling the collection aborts the attempt and runs the `/unpair`
     * cleanup, so the host stops showing its PIN prompt (spec §4.8).
     */
    fun pair(host: KnownHost): Flow<PairProgress> = flow {
        val resolved = resolver.resolve(host, ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS)
        if (resolved == null) {
            ProtocolLog.w(ProtocolLog.TAG_PAIR, "Cannot pair: ${host.name} is unreachable")
            emit(PairProgress.Done(PairResult.FAILED, "${host.name} did not answer"))
            return@flow
        }
        resolver.rememberHttpsPort(host.uuid, resolved.serverInfo.httpsPort)
        emitAll(
            engine.pair(
                hostKey = host.uuid,
                address = resolved.address,
                serverInfo = resolved.serverInfo,
            ),
        )
    }

    /**
     * Drops the pairing, both locally and on the host (spec §3.9).
     *
     * The local pin is always removed, even when the host cannot be reached: the user asked to
     * forget this PC, and whether the PC is switched on is beside the point.
     */
    suspend fun unpair(host: KnownHost): Boolean {
        val address = resolver.resolve(host, ProtocolConstants.PROBE_TIMEOUT_OFFLINE_MS)?.address
            ?: HostAddress.parse(host.primaryAddress)
        if (address == null) {
            ProtocolLog.w(ProtocolLog.TAG_PAIR, "Unpair: ${host.name} has no usable address")
            return false
        }
        return engine.unpair(host.uuid, address)
    }
}
