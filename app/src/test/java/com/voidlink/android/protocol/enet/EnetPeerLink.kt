package com.voidlink.android.protocol.enet

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

/**
 * A clock the test drives by hand.
 *
 * Retransmission is the part of ENet most worth testing and least worth testing with real sleeps:
 * every timing assertion below is exact because the clock only moves when a test moves it.
 */
class MutableEnetClock(var timeMs: Int = 0) : EnetClock {
    override fun nowMs(): Int = timeMs
}

/**
 * How badly the link between two peers misbehaves.
 *
 * `04-ROADMAP.md` Phase 8 makes "injected loss and reordering" the gate for the whole control
 * stream, and the point of injecting it here rather than on a socket is that a seeded [Random]
 * makes a failure reproducible instead of a once-a-week CI mystery.
 *
 * @property dropPercent chance that a datagram is thrown away outright.
 * @property duplicatePercent chance that a datagram is delivered twice.
 * @property holdPercent chance that a datagram is held back a step, so it arrives after one sent
 *   later — reordering across steps rather than only within a batch.
 * @property reorder whether each delivered batch is reversed.
 */
data class EnetLinkFaults(
    val dropPercent: Int = 0,
    val duplicatePercent: Int = 0,
    val holdPercent: Int = 0,
    val reorder: Boolean = false,
)

/**
 * Two [EnetPeer]s wired to each other through a fault-injecting link, with no sockets and no
 * real time (`docs/01-PROTOCOL.md` §9.1).
 *
 * This is the deterministic half of the ENet test strategy. `EnetLoopbackTest` proves the same
 * things over real UDP through [EnetHost]; this proves them over a link whose every drop,
 * duplicate and reordering is chosen by a seeded generator, so a convergence failure is a bug
 * rather than a bad afternoon on the CI machine.
 *
 * The routing here mirrors the two dozen lines of [EnetHost] that dispatch commands to a peer and
 * answer an inbound CONNECT — deliberately, so that a peer can be exercised without a service loop
 * or a dispatcher. The socket path itself is covered by `EnetLoopbackTest`.
 *
 * @param config shared by both peers; give the tests a short RTO and generous dead-peer timeouts.
 * @param faults what the link does to datagrams.
 * @param seed fixes the fault pattern.
 */
class EnetPeerLink(
    private val config: EnetConfig,
    private val faults: EnetLinkFaults = EnetLinkFaults(),
    seed: Int = 20250812,
) {
    /** The shared clock. Advanced by [step]. */
    val clock = MutableEnetClock()

    /** The connecting side. */
    val client = EnetPeer(SERVER_ADDRESS, config, incomingPeerId = 0, connectId = CLIENT_CONNECT_ID)

    /** The accepting side, created when the first CONNECT arrives. */
    var server: EnetPeer? = null
        private set

    /** Payloads the client has been handed, in delivery order. */
    val clientPayloads = ArrayList<ByteArray>()

    /** Payloads the server has been handed, in delivery order. */
    val serverPayloads = ArrayList<ByteArray>()

    /** Every event either peer has raised, newest last. */
    val clientEvents = ArrayList<EnetEvent>()
    val serverEvents = ArrayList<EnetEvent>()

    /**
     * Every command each peer put on the wire, in order, including retransmissions.
     *
     * Recorded by decoding the datagrams the peer produced — which is also a standing round-trip
     * check on the codec, since anything the encoder writes that the decoder cannot read shows up
     * here as a missing command.
     */
    val clientSentCommands = ArrayList<EnetCommand>()
    val serverSentCommands = ArrayList<EnetCommand>()

    /** Datagrams thrown away by the link so far. Asserted on to prove loss really happened. */
    var dropped: Int = 0
        private set

    /** Datagrams the link delivered twice. */
    var duplicated: Int = 0
        private set

    private val random = Random(seed)
    private val toServer = ArrayList<ByteArray>()
    private val toClient = ArrayList<ByteArray>()
    private val heldForServer = ArrayList<ByteArray>()
    private val heldForClient = ArrayList<ByteArray>()

    private val clientScratch = ArrayList<EnetEvent>()
    private val serverScratch = ArrayList<EnetEvent>()

    /** Queues the client's CONNECT. Nothing moves until [step] is called. */
    fun startConnect(connectData: Int = CONNECT_DATA) {
        client.startConnect(clock.timeMs, connectData)
    }

    /**
     * Advances the clock by [deltaMs], delivers whatever the link is holding, and services both
     * peers. One step is one hop, so a round trip takes two.
     */
    fun step(deltaMs: Int = 10) {
        clock.timeMs += deltaMs
        deliverToServer()
        deliverToClient()
        serviceClient()
        serviceServer()
    }

    /** Runs [steps] steps unconditionally. */
    fun run(steps: Int, deltaMs: Int = 10) {
        repeat(steps) { step(deltaMs) }
    }

    /**
     * Steps until [condition] holds or [maxSteps] have passed.
     *
     * @return true when the condition was met, so a test can assert on it and report how long it
     *   took rather than timing out somewhere unhelpful.
     */
    fun runUntil(maxSteps: Int = 2_000, deltaMs: Int = 10, condition: () -> Boolean): Boolean {
        repeat(maxSteps) {
            if (condition()) return true
            step(deltaMs)
        }
        return condition()
    }

    /** True once both peers consider themselves connected. */
    fun bothConnected(): Boolean =
        client.state == EnetPeerState.CONNECTED && server?.state == EnetPeerState.CONNECTED

    private fun serviceClient() {
        clientScratch.clear()
        for (datagram in client.service(clock.timeMs, clientScratch)) {
            record(datagram, clientSentCommands)
            enqueue(toServer, datagram)
        }
        collect(clientScratch, clientEvents, clientPayloads)
    }

    private fun serviceServer() {
        val current = server ?: return
        serverScratch.clear()
        for (datagram in current.service(clock.timeMs, serverScratch)) {
            record(datagram, serverSentCommands)
            enqueue(toClient, datagram)
        }
        collect(serverScratch, serverEvents, serverPayloads)
    }

    private fun record(datagram: ByteArray, into: MutableList<EnetCommand>) {
        val parsed = EnetPacketCodec.decode(datagram) ?: return
        into.addAll(parsed.commands)
    }

    private fun collect(
        scratch: List<EnetEvent>,
        events: MutableList<EnetEvent>,
        payloads: MutableList<ByteArray>,
    ) {
        for (event in scratch) {
            events.add(event)
            if (event is EnetEvent.Received) payloads.add(event.payload)
        }
    }

    private fun deliverToServer() {
        for (raw in take(toServer, heldForServer)) {
            val parsed = EnetPacketCodec.decode(raw) ?: continue
            val existing = server
            if (existing == null) {
                var connect: EnetCommand.Connect? = null
                for (command in parsed.commands) {
                    if (command is EnetCommand.Connect) {
                        connect = command
                        break
                    }
                }
                val request = connect ?: continue
                val created = EnetPeer(CLIENT_ADDRESS, config, incomingPeerId = 0, connectId = request.connectId)
                server = created
                created.acceptConnect(request, clock.timeMs)
                continue
            }
            serverScratch.clear()
            for (command in parsed.commands) {
                existing.handleCommand(command, parsed.header.sentTime, clock.timeMs, serverScratch)
            }
            collect(serverScratch, serverEvents, serverPayloads)
        }
    }

    private fun deliverToClient() {
        for (raw in take(toClient, heldForClient)) {
            val parsed = EnetPacketCodec.decode(raw) ?: continue
            clientScratch.clear()
            for (command in parsed.commands) {
                client.handleCommand(command, parsed.header.sentTime, clock.timeMs, clientScratch)
            }
            collect(clientScratch, clientEvents, clientPayloads)
        }
    }

    /** Applies loss and duplication as a datagram enters the link. */
    private fun enqueue(queue: MutableList<ByteArray>, datagram: ByteArray) {
        if (faults.dropPercent > 0 && random.nextInt(100) < faults.dropPercent) {
            dropped++
            return
        }
        queue.add(datagram)
        if (faults.duplicatePercent > 0 && random.nextInt(100) < faults.duplicatePercent) {
            duplicated++
            queue.add(datagram)
        }
    }

    /** Applies holding and reordering as datagrams leave the link. */
    private fun take(queue: MutableList<ByteArray>, held: MutableList<ByteArray>): List<ByteArray> {
        val batch = ArrayList<ByteArray>(held)
        held.clear()
        for (datagram in queue) {
            if (faults.holdPercent > 0 && random.nextInt(100) < faults.holdPercent) {
                held.add(datagram)
            } else {
                batch.add(datagram)
            }
        }
        queue.clear()
        if (faults.reorder && batch.size > 1) batch.reverse()
        return batch
    }

    companion object {
        /** Fixed so the CONNECT the client sends is byte-for-byte predictable. */
        const val CLIENT_CONNECT_ID: Int = 0x1A2B3C4D

        /** Stands in for the `X-SS-Connect-Data` value of spec §6.3. */
        const val CONNECT_DATA: Int = 0x0BADF00D

        /** Addresses are only identity here; nothing in this harness opens a socket. */
        val SERVER_ADDRESS: InetSocketAddress =
            InetSocketAddress(InetAddress.getLoopbackAddress(), EnetControlConstants.DEFAULT_CONTROL_PORT)

        /** @see SERVER_ADDRESS */
        val CLIENT_ADDRESS: InetSocketAddress =
            InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000)

        /**
         * A config tuned for tests: a short retransmission timeout so loss recovery takes tens of
         * milliseconds of virtual time, and every give-up deadline pushed far enough out that no
         * amount of injected loss can make a test fail by declaring the link dead.
         *
         * The connect timeout in particular is *not* spec §9.1's ten seconds. That number is a
         * product decision about how long a user stares at a spinner; a test that inherits it is
         * really measuring how a seeded coin came up, and the tests that care about the deadline
         * itself set their own.
         */
        fun testConfig(acceptIncomingConnections: Boolean = false): EnetConfig = EnetConfig(
            channelCount = EnetControlConstants.CHANNEL_COUNT,
            serviceIntervalMs = 5,
            pingIntervalMs = 500,
            initialRoundTripTimeMs = 40,
            connectTimeoutMs = 60_000,
            timeoutLimit = 32,
            timeoutMinimumMs = 60_000,
            timeoutMaximumMs = 120_000,
            acceptIncomingConnections = acceptIncomingConnections,
        )
    }
}
