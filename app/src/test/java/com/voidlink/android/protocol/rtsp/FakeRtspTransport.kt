package com.voidlink.android.protocol.rtsp

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * What the fake host does when it receives a request.
 *
 * The four cases are deliberately the four the error classification has to tell apart, so a test
 * can drive each one at any step of the handshake.
 */
sealed interface FakeReply {

    /** Answer with this exact RTSP message. */
    class Respond(val text: String) : FakeReply

    /** Say nothing until the read deadline expires. */
    object Timeout : FakeReply

    /** Break the connection with an I/O error. */
    class Fail(val message: String) : FakeReply

    /** Close the connection cleanly without answering. */
    object CloseConnection : FakeReply
}

/**
 * A scripted [RtspTransport] with no socket behind it.
 *
 * It works at the byte level, exactly like the real one, so a test that drives it exercises request
 * serialisation, response framing and parsing for real — the only thing faked is the wire. Response
 * bytes are handed back at most [chunkSize] at a time, which lets a test prove the framing survives
 * a response arriving in pieces.
 *
 * Assumes [RtspConnection] writes each request in a single [write] call, which it does.
 *
 * @param chunkSize maximum bytes returned per [read]; the default is larger than any test message.
 * @param responder what the fake host answers, given the request it just received.
 */
class FakeRtspTransport(
    private val chunkSize: Int = Int.MAX_VALUE,
    private val responder: (RtspRequest) -> FakeReply,
) : RtspTransport {

    /** Every request the fake received, in order. */
    val requests: MutableList<RtspRequest> = ArrayList()

    /** The raw bytes of every request, as text, for byte-exact assertions. */
    val rawRequests: MutableList<String> = ArrayList()

    /** Set to make [connect] fail. */
    var connectFailure: Throwable? = null

    var connected: Boolean = false
        private set

    var closeCount: Int = 0
        private set

    private var action: FakeReply? = null
    private var pendingBytes: ByteArray = ByteArray(0)
    private var pendingOffset: Int = 0

    override suspend fun connect(timeoutMs: Int) {
        val failure = connectFailure
        if (failure != null) throw failure
        connected = true
    }

    override suspend fun write(bytes: ByteArray, timeoutMs: Int) {
        if (!connected) throw IOException("fake transport is not connected")
        val text = String(bytes, Charsets.UTF_8)
        val request = parseRequest(text)
        rawRequests.add(text)
        requests.add(request)
        val reply = responder(request)
        action = reply
        pendingBytes = if (reply is FakeReply.Respond) reply.text.toByteArray(Charsets.UTF_8)
        else ByteArray(0)
        pendingOffset = 0
    }

    override suspend fun read(destination: ByteArray, timeoutMs: Int): Int {
        if (!connected) throw IOException("fake transport is not connected")
        val current = action ?: throw SocketTimeoutException("the fake host had nothing more to send")
        when (current) {
            is FakeReply.Timeout -> throw SocketTimeoutException("fake read timed out after $timeoutMs ms")
            is FakeReply.Fail -> throw IOException(current.message)
            is FakeReply.CloseConnection -> return -1
            is FakeReply.Respond -> Unit
        }
        val remaining = pendingBytes.size - pendingOffset
        if (remaining <= 0) {
            action = null
            throw SocketTimeoutException("the fake host already delivered its whole answer")
        }
        val count = minOf(remaining, destination.size, chunkSize)
        System.arraycopy(pendingBytes, pendingOffset, destination, 0, count)
        pendingOffset += count
        if (pendingOffset >= pendingBytes.size) action = null
        return count
    }

    override fun close() {
        closeCount++
        connected = false
    }

    companion object {

        /**
         * Parses a request the way a host would, so tests can assert on methods, targets and
         * headers instead of on substrings.
         */
        fun parseRequest(text: String): RtspRequest {
            val separator = text.indexOf("\r\n\r\n")
            val head = if (separator < 0) text else text.substring(0, separator)
            val body = if (separator < 0) "" else text.substring(separator + 4)
            val lines = head.split("\r\n").filter { it.isNotEmpty() }
            val requestLine = lines.firstOrNull().orEmpty().split(' ')
            val headers = ArrayList<Pair<String, String>>(lines.size)
            for (index in 1 until lines.size) {
                val colon = lines[index].indexOf(':')
                if (colon <= 0) continue
                headers.add(
                    lines[index].substring(0, colon).trim() to
                        lines[index].substring(colon + 1).trim(),
                )
            }
            return RtspRequest(
                method = requestLine.getOrElse(0) { "" },
                target = requestLine.getOrElse(1) { "" },
                headers = headers,
                body = body.ifEmpty { null },
            )
        }
    }
}
