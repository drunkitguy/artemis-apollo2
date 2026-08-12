package com.voidlink.android.protocol.rtsp

import java.io.IOException

/**
 * The byte-level duplex link the RTSP handshake runs over (spec §6.1).
 *
 * Deliberately as small and as dumb as it can be: connect, write bytes, read bytes, close. All the
 * interesting behaviour — framing, `CSeq`, header assembly, error classification — lives above it
 * in [RtspConnection], which means the whole of that behaviour is exercised by a fake that does
 * nothing but hand back canned bytes. A fatter interface (one that exchanged parsed messages)
 * would have moved framing into the untested socket implementation, which is exactly the part no
 * unit test can reach.
 *
 * **Error contract**, and it is the whole reason failures can be told apart afterwards:
 *
 * * a step that ran out of time throws [java.net.SocketTimeoutException];
 * * any other failure to reach or keep the host throws some other [IOException];
 * * end of stream is reported by [read] returning `-1`, never by an exception.
 *
 * Implementations may block; callers are expected to be on an I/O dispatcher.
 */
interface RtspTransport {

    /**
     * Opens the link.
     *
     * @param timeoutMs connect deadline in milliseconds.
     * @throws java.net.SocketTimeoutException if the host did not answer in time.
     * @throws IOException if the connection was refused or failed otherwise.
     */
    suspend fun connect(timeoutMs: Int)

    /**
     * Writes [bytes] in full.
     *
     * @param timeoutMs write deadline in milliseconds.
     */
    suspend fun write(bytes: ByteArray, timeoutMs: Int)

    /**
     * Reads whatever is available into [destination].
     *
     * @param timeoutMs read deadline in milliseconds.
     * @return the number of bytes read, or `-1` at end of stream.
     */
    suspend fun read(destination: ByteArray, timeoutMs: Int): Int

    /** Releases the link. Must be safe to call twice, and safe to call on a never-connected link. */
    fun close()
}

/**
 * Creates an [RtspTransport] for one host.
 *
 * Exists so [RtspSessionNegotiator] can be constructed with a fake in tests without any of its
 * callers knowing there is a socket underneath.
 */
fun interface RtspTransportFactory {

    /**
     * @param host bare host, without IPv6 brackets.
     * @param port the RTSP port, normally 48010 or whatever `sessionUrl0` carried.
     */
    fun create(host: String, port: Int): RtspTransport
}
