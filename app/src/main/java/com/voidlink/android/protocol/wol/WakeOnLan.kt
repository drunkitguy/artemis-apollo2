package com.voidlink.android.protocol.wol

import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.UnverifiedProtocolConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Wake-on-LAN magic packets (spec §1.4).
 *
 * A magic packet is six `0xFF` bytes followed by the target MAC repeated sixteen times, and it is
 * sprayed rather than aimed: to every interface's subnet broadcast address, to the global
 * broadcast address, and to the host's last known unicast address, on each of the candidate ports.
 * Nothing acknowledges it, so redundancy is the only reliability mechanism available.
 */
object WakeOnLan {

    /**
     * Builds the 102-byte magic packet for [mac].
     *
     * Pure and separate from the sending so the byte layout is unit-testable — the one part of WoL
     * that can actually be verified without a sleeping PC on the other end.
     *
     * @param mac the six-byte hardware address.
     */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == ProtocolConstants.WOL_MAC_BYTES) {
            "MAC must be ${ProtocolConstants.WOL_MAC_BYTES} bytes, was ${mac.size}"
        }
        val packet = ByteArray(ProtocolConstants.WOL_PACKET_BYTES)
        for (index in 0 until ProtocolConstants.WOL_SYNC_STREAM_BYTES) {
            packet[index] = 0xFF.toByte()
        }
        var offset = ProtocolConstants.WOL_SYNC_STREAM_BYTES
        repeat(ProtocolConstants.WOL_MAC_REPEAT_COUNT) {
            System.arraycopy(mac, 0, packet, offset, mac.size)
            offset += mac.size
        }
        return packet
    }

    /**
     * Parses a MAC in any of the forms a host might report it: `aa:bb:cc:dd:ee:ff`,
     * `aa-bb-cc-dd-ee-ff`, or twelve bare hex digits.
     *
     * @return the six bytes, or `null` when [text] is not a usable MAC — including the
     *   all-zero placeholder Sunshine returns over plaintext HTTP (spec §1.4), which means
     *   "I am not telling you" rather than a real address.
     */
    fun parseMac(text: String?): ByteArray? {
        val cleaned = text?.trim()?.replace(":", "")?.replace("-", "")?.replace(".", "").orEmpty()
        if (cleaned.length != ProtocolConstants.WOL_MAC_BYTES * 2) return null
        val bytes = ByteArray(ProtocolConstants.WOL_MAC_BYTES)
        for (index in bytes.indices) {
            val value = cleaned.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            bytes[index] = value.toByte()
        }
        return if (bytes.all { it == 0.toByte() }) null else bytes
    }

    /**
     * Sends a magic packet for [macText] everywhere it might plausibly be heard.
     *
     * @param macText the host's MAC as stored.
     * @param lastKnownAddress the host's last known unicast address, tried in addition to the
     *   broadcasts because some setups keep an ARP entry alive across a sleep.
     * @return true when at least one datagram left the device. This is not a guarantee that the
     *   PC woke — nothing in the protocol can tell us that — only that we did our part.
     */
    suspend fun wake(macText: String?, lastKnownAddress: String?): Boolean = withContext(Dispatchers.IO) {
        val mac = parseMac(macText)
        if (mac == null) {
            ProtocolLog.w(ProtocolLog.TAG_WOL, "No usable MAC on record; cannot send a magic packet")
            return@withContext false
        }
        // UNVERIFIED(spec 01 §1.4): whether hosts listen on any port besides 9 and 7. Sending to
        // both is the common practice and costs one extra datagram.
        ProtocolLog.unverified(
            ProtocolLog.TAG_WOL,
            "wol-ports",
            "sending magic packets to UDP ports ${UnverifiedProtocolConstants.WOL_PORTS} " +
                "(spec 01 §1.4)",
        )

        val payload = magicPacket(mac)
        val targets = buildTargets(lastKnownAddress)
        if (targets.isEmpty()) {
            ProtocolLog.w(ProtocolLog.TAG_WOL, "No broadcast targets available")
            return@withContext false
        }

        var sent = 0
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in targets) {
                    for (port in UnverifiedProtocolConstants.WOL_PORTS) {
                        val result = runCatching {
                            socket.send(DatagramPacket(payload, payload.size, target, port))
                        }
                        if (result.isSuccess) sent++
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ProtocolLog.w(ProtocolLog.TAG_WOL, "Wake-on-LAN send failed", t)
        }
        ProtocolLog.i(ProtocolLog.TAG_WOL, "Sent $sent magic packet(s)")
        sent > 0
    }

    /**
     * Collects every address worth aiming at: each up interface's subnet broadcast, the global
     * broadcast, and the host's last known unicast address.
     */
    private fun buildTargets(lastKnownAddress: String?): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()

        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (networkInterface in interfaces) {
                if (!runCatching { networkInterface.isUp }.getOrDefault(false)) continue
                if (runCatching { networkInterface.isLoopback }.getOrDefault(true)) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    interfaceAddress?.broadcast?.let { targets.add(it) }
                }
            }
        }.onFailure {
            ProtocolLog.d(ProtocolLog.TAG_WOL, "Could not enumerate interfaces: ${it.message}")
        }

        runCatching { InetAddress.getByName(ProtocolConstants.WOL_GLOBAL_BROADCAST) }
            .getOrNull()
            ?.let { targets.add(it) }

        val unicast = lastKnownAddress?.trim()?.takeIf { it.isNotEmpty() }
        if (unicast != null) {
            // Usually an IP literal, which resolves without a lookup. A hostname would cost a DNS
            // round trip for a machine that is asleep and almost certainly unregistered, so the
            // failure is logged and skipped rather than retried.
            runCatching { InetAddress.getByName(unicast) }
                .onFailure { ProtocolLog.d(ProtocolLog.TAG_WOL, "Cannot resolve $unicast") }
                .getOrNull()
                ?.let { targets.add(it) }
        }

        return targets.toList()
    }
}
