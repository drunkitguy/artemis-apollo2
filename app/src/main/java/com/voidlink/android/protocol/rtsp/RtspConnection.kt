package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.ProtocolLog
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The outcome of one request/response exchange.
 *
 * [Answered] only ever carries a `200`: spec §6.3 requires it at every step, so a non-200 is turned
 * into an [RtspError.Refused] here rather than being handed upwards for each caller to re-check.
 */
sealed interface RtspExchange {

    /** The host answered `200`. */
    class Answered(val request: RtspRequest, val response: RtspResponse) : RtspExchange

    /** The exchange failed, already classified. */
    class Failed(val error: RtspError) : RtspExchange
}

/**
 * One RTSP conversation: `CSeq` allocation, header assembly, response framing, error
 * classification (spec §6.2, §6.3).
 *
 * Holds all the behaviour that a socket would otherwise hide. The transport underneath does nothing
 * but move bytes, so every rule in here — the forgiving framing, the `CSeq` bookkeeping, the
 * mapping from an `IOException` to one of four distinguishable failures — is reachable from a test
 * with no network at all.
 *
 * **The failure mapping is the point.** A refusal, a timeout, a malformed answer and an unreachable
 * host are four different things with four different remedies, and the one thing that must never
 * happen is for them to arrive at the UI as the same shrug.
 *
 * Not thread-safe: one connection, one caller, one exchange at a time, which is exactly how the
 * handshake runs.
 *
 * @param transport the link to run over.
 */
class RtspConnection(private val transport: RtspTransport) {

    private var cseqCounter = 1
    private val accumulator = ByteArrayOutputStream(RtspConstants.READ_CHUNK_BYTES)
    private val chunk = ByteArray(RtspConstants.READ_CHUNK_BYTES)

    /** The `CSeq` the next request will carry. Requests are numbered from 1 (spec §6.2). */
    val nextCseq: Int get() = cseqCounter

    /**
     * Opens the underlying link.
     *
     * @return `null` on success, or the classified failure.
     */
    suspend fun connect(timeoutMs: Int): RtspError? = try {
        transport.connect(timeoutMs)
        null
    } catch (timeout: SocketTimeoutException) {
        RtspError.Timeout(RtspStep.CONNECT, timeoutMs.toLong())
    } catch (failure: IOException) {
        RtspError.Unreachable(RtspStep.CONNECT, describe(failure), failure)
    }

    /**
     * Sends one request and reads its response.
     *
     * `CSeq` and `X-GS-ClientVersion` are prepended to every request here so no call site can
     * forget either; [headers] carries only what the method itself needs, in the order spec §6.3
     * writes it. When [body] is present, `Content-type` and `Content-length` are appended after
     * [headers] — again matching the spec's own ANNOUNCE example.
     *
     * @param step which handshake step this is; every error names it.
     * @param timeoutMs this step's own deadline.
     */
    suspend fun exchange(
        step: RtspStep,
        method: String,
        target: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        timeoutMs: Int,
    ): RtspExchange {
        val cseq = cseqCounter
        cseqCounter++

        val allHeaders = ArrayList<Pair<String, String>>(headers.size + 4)
        allHeaders.add(RtspConstants.HEADER_CSEQ to cseq.toString())
        allHeaders.add(RtspConstants.HEADER_CLIENT_VERSION to RtspConstants.CLIENT_VERSION)
        allHeaders.addAll(headers)
        if (body != null) {
            allHeaders.add(RtspConstants.HEADER_CONTENT_TYPE to RtspConstants.MIME_SDP)
            allHeaders.add(
                RtspConstants.HEADER_CONTENT_LENGTH to
                    body.toByteArray(Charsets.UTF_8).size.toString(),
            )
        }

        val request = RtspRequest(method, target, allHeaders, body)
        ProtocolLog.d(RtspConstants.TAG, "-> $request")

        val response = try {
            transport.write(request.encode(), timeoutMs)
            readResponse(timeoutMs)
        } catch (timeout: SocketTimeoutException) {
            return RtspExchange.Failed(RtspError.Timeout(step, timeoutMs.toLong()))
        } catch (endOfStream: EOFException) {
            return RtspExchange.Failed(
                RtspError.Unreachable(step, describe(endOfStream), endOfStream),
            )
        } catch (failure: IOException) {
            return RtspExchange.Failed(RtspError.Unreachable(step, describe(failure), failure))
        }

        if (response == null) {
            return RtspExchange.Failed(
                RtspError.Malformed(step, "no RTSP status line in the host's answer"),
            )
        }

        ProtocolLog.d(RtspConstants.TAG, "<- ${step.label}: $response")

        // Spec §6.2 warns that some GFE builds mis-order or omit CSeq. It is worth a line in the
        // log and nothing more: failing here would abandon a session over a cosmetic defect.
        val echoed = response.cseq
        if (echoed != null && echoed != cseq) {
            ProtocolLog.w(
                RtspConstants.TAG,
                "${step.label}: host echoed CSeq $echoed for our request $cseq (spec §6.2 quirk)",
            )
        }

        if (!response.isOk) {
            return RtspExchange.Failed(
                RtspError.Refused(step, response.statusCode, response.reasonPhrase),
            )
        }
        return RtspExchange.Answered(request, response)
    }

    /** Closes the link. Safe to call more than once. */
    fun close() {
        transport.close()
    }

    /**
     * Reads until one complete message is buffered.
     *
     * @return the parsed response, or `null` when what arrived is not an RTSP message at all.
     * @throws EOFException if the host closed the connection mid-message.
     */
    private suspend fun readResponse(timeoutMs: Int): RtspResponse? {
        while (true) {
            val buffered = accumulator.toByteArray()
            val total = RtspMessageCodec.completeMessageLength(buffered, buffered.size)
            if (total >= 0) {
                val response = RtspMessageCodec.parseResponse(buffered, total)
                accumulator.reset()
                if (buffered.size > total) {
                    // A host that pipelines, or a stray byte: keep it for the next read rather than
                    // dropping it and desynchronising every exchange after this one.
                    accumulator.write(buffered, total, buffered.size - total)
                }
                return response
            }
            if (buffered.size >= RtspConstants.MAX_RESPONSE_BYTES) {
                ProtocolLog.w(
                    RtspConstants.TAG,
                    "abandoning an RTSP response after ${buffered.size} bytes with no complete " +
                        "message; treating it as malformed",
                )
                accumulator.reset()
                return null
            }
            val read = transport.read(chunk, timeoutMs)
            if (read < 0) {
                throw EOFException(
                    if (buffered.isEmpty()) "the host closed the RTSP connection without answering"
                    else "the host closed the RTSP connection after ${buffered.size} bytes",
                )
            }
            if (read > 0) accumulator.write(chunk, 0, read)
        }
    }

    private fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
}
