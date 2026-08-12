package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.ProtocolLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The real [RtspTransport]: a plain TCP socket to the host's RTSP port (spec §6.1).
 *
 * v1 speaks TCP only. A host advertising `rtspru://` wants RTSP over ENet on the same port number,
 * and spec §6.1's rule is to connect over TCP anyway because Sunshine listens on both — see
 * [UnverifiedRtspConstants.RTSPRU_KEEPS_TCP_LISTENER], which is where that assumption is recorded
 * and where a failure to connect to a Sunshine host should send you first.
 *
 * **Timeouts are enforced by `SO_TIMEOUT`, not by `withTimeout`.** A blocking socket read does not
 * observe coroutine cancellation, so wrapping it in a coroutine timeout would report a timeout
 * while leaving the read running on a thread that nothing can reclaim. Setting the socket option
 * per call makes the read itself give up, which is both accurate and cleanable. Writes are not
 * covered by `SO_TIMEOUT`; a request is a couple of hundred bytes and cannot fill a socket buffer,
 * so there is nothing there to bound.
 *
 * @param host bare host, no IPv6 brackets.
 * @param port RTSP port, from `sessionUrl0` or [com.voidlink.android.protocol.ProtocolConstants.DEFAULT_RTSP_PORT].
 */
class SocketRtspTransport(
    private val host: String,
    private val port: Int,
) : RtspTransport {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun connect(timeoutMs: Int) {
        withContext(Dispatchers.IO) {
            val opened = Socket()
            try {
                // Nagle would coalesce our request with nothing and add a round trip of latency to
                // every step of an eight-step handshake (spec §6.1).
                opened.tcpNoDelay = true
                opened.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (error: Throwable) {
                closeQuietly(opened)
                throw error
            }
            socket = opened
            input = opened.getInputStream()
            output = opened.getOutputStream()
            ProtocolLog.i(RtspConstants.TAG, "RTSP connected to $host:$port")
        }
    }

    override suspend fun write(bytes: ByteArray, timeoutMs: Int) {
        val stream = output ?: throw IOException("RTSP socket is not connected")
        withContext(Dispatchers.IO) {
            stream.write(bytes)
            stream.flush()
        }
    }

    override suspend fun read(destination: ByteArray, timeoutMs: Int): Int {
        val stream = input ?: throw IOException("RTSP socket is not connected")
        val active = socket ?: throw IOException("RTSP socket is not connected")
        return withContext(Dispatchers.IO) {
            active.soTimeout = timeoutMs
            stream.read(destination)
        }
    }

    override fun close() {
        val open = socket
        socket = null
        input = null
        output = null
        if (open != null) closeQuietly(open)
    }

    private fun closeQuietly(target: Socket) {
        try {
            target.close()
        } catch (error: IOException) {
            ProtocolLog.d(RtspConstants.TAG, "ignoring error closing the RTSP socket: ${error.message}")
        }
    }

    companion object {
        /** The factory production code passes to [RtspSessionNegotiator]. */
        val FACTORY: RtspTransportFactory = RtspTransportFactory { host, port ->
            SocketRtspTransport(host, port)
        }
    }
}
