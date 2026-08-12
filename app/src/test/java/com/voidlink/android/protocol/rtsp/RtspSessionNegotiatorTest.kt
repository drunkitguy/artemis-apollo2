package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.http.AppVersion
import com.voidlink.android.protocol.http.AudioChannelLayout
import com.voidlink.android.protocol.http.LaunchResponse
import com.voidlink.android.protocol.http.ServerKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Drives the whole handshake of `docs/01-PROTOCOL.md` §6.3 against a scripted fake host.
 *
 * Two things are being proved here, and the second matters as much as the first:
 *
 * 1. **The sequence is right** — the methods, their order, the stream-id targets per generation,
 *    `CSeq` counting from 1, and the session id propagating from the audio SETUP onwards.
 * 2. **Failures stay distinguishable** — a refusal, a timeout, a malformed answer and an
 *    unreachable host each produce their own [RtspError], naming the step they happened at. That
 *    distinction is the whole reason the result type has five shapes instead of one.
 */
class RtspSessionNegotiatorTest {

    // ---- The happy path --------------------------------------------------------------------------

    @Test
    fun `the full sequence runs in the order the spec mandates`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val result = negotiate(transport)

        assertTrue(describe(result), result.isSuccess)
        assertEquals(
            listOf("OPTIONS", "DESCRIBE", "SETUP", "SETUP", "SETUP", "ANNOUNCE", "PLAY", "PLAY"),
            transport.requests.map { it.method },
        )
        assertEquals(
            listOf(
                "rtsp://192.168.1.50:48010",
                "rtsp://192.168.1.50:48010",
                "rtsp://192.168.1.50:48010/streamid=audio/0/0",
                "rtsp://192.168.1.50:48010/streamid=video/0/0",
                "rtsp://192.168.1.50:48010/streamid=control/13/0",
                "rtsp://192.168.1.50:48010/streamid=control/13/0",
                "rtsp://192.168.1.50:48010/streamid=video",
                "rtsp://192.168.1.50:48010/streamid=audio",
            ),
            transport.requests.map { it.target },
        )
    }

    @Test
    fun `CSeq starts at one and increments once per request`() {
        val transport = FakeRtspTransport(responder = successResponder())
        negotiate(transport)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), transport.requests.map { it.cseq })
    }

    @Test
    fun `every request carries the client version header`() {
        val transport = FakeRtspTransport(responder = successResponder())
        negotiate(transport)
        for (request in transport.requests) {
            assertEquals(
                "missing X-GS-ClientVersion on ${request.method}",
                "14",
                headerOf(request, RtspConstants.HEADER_CLIENT_VERSION),
            )
        }
    }

    @Test
    fun `the session id comes from the audio SETUP and is carried by everything after it`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val result = negotiate(transport)

        // The audio SETUP is what creates the session, so it cannot reference one.
        assertNull(headerOf(transport.requests[2], RtspConstants.HEADER_SESSION))
        for (index in 3 until transport.requests.size) {
            assertEquals(
                "wrong session on request $index",
                "DEADBEEFCAFE",
                headerOf(transport.requests[index], RtspConstants.HEADER_SESSION),
            )
        }
        assertEquals("DEADBEEFCAFE", session(result).sessionId)
    }

    @Test
    fun `the negotiated result carries every port, payload and flag the media layers need`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val session = session(negotiate(transport))

        assertEquals("192.168.1.50", session.host)
        assertEquals(48010, session.rtspPort)
        assertEquals(48000, session.audioPort)
        assertEquals(47998, session.videoPort)
        assertEquals(47999, session.controlPort)
        assertTrue(session.controlSetupPerformed)
        assertEquals(AUDIO_PING, session.audioPingPayload)
        assertEquals(VIDEO_PING, session.videoPingPayload)
        assertEquals(42, session.controlConnectData)
        assertEquals(VideoCodec.H264, session.codec)
        assertFalse(session.hdr)
        assertEquals(1392, session.packetSize)
        assertEquals(0, session.encryptionFlags)
        assertEquals(20_000, session.bitrateKbps)
        assertEquals(20_000, session.configuredBitrateKbps)
        assertEquals(AudioChannelLayout.STEREO, session.audioLayout)
        assertFalse(session.audioLayoutDowngraded)
        assertArrayEquals(intArrayOf(0, 1), session.opusConfig.mapping)
        assertNotNull(session.hostDescription.attribute("x-nv-general.featureFlags"))
    }

    @Test
    fun `the ANNOUNCE body is the golden SDP for the configuration`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val result = negotiate(transport)

        val announce = transport.requests.first { it.method == RtspConstants.METHOD_ANNOUNCE }
        assertEquals(SdpGoldens.SDP_1080P60_H264_STEREO_SUNSHINE, announce.body)
        assertEquals(RtspConstants.MIME_SDP, headerOf(announce, RtspConstants.HEADER_CONTENT_TYPE))
        assertEquals(
            SdpGoldens.SDP_1080P60_H264_STEREO_SUNSHINE.toByteArray(Charsets.UTF_8).size.toString(),
            headerOf(announce, RtspConstants.HEADER_CONTENT_LENGTH),
        )
        assertEquals(SdpGoldens.SDP_1080P60_H264_STEREO_SUNSHINE, session(result).announcedSdp)
    }

    @Test
    fun `the ANNOUNCE that actually goes on the wire carries the raw configured bitrate`() {
        // Belt and braces over the golden: omitting this attribute makes Apollo encode at ~0.64x,
        // and the symptom is invisible (docs/05-DYNAMIC-BITRATE.md §1.3).
        val transport = FakeRtspTransport(responder = successResponder())
        val result = negotiate(
            transport,
            configuration = SdpGoldens.config1080p60H264Stereo()
                .copy(bitrateKbps = 16_000, configuredBitrateKbps = 20_000),
        )

        val announce = transport.requests.first { it.method == RtspConstants.METHOD_ANNOUNCE }
        val body = announce.body
        assertNotNull(body)
        assertTrue(body!!.contains("a=x-ml-video.configuredBitrateKbps:20000\r\n"))
        assertTrue(body.contains("a=x-nv-vqos[0].bw.maximumBitrateKbps:16000\r\n"))
        assertEquals(20_000, session(result).configuredBitrateKbps)
        assertEquals(16_000, session(result).bitrateKbps)
    }

    @Test
    fun `DESCRIBE and every SETUP send the If-Modified-Since header some GFE builds require`() {
        val transport = FakeRtspTransport(responder = successResponder())
        negotiate(transport)

        for (index in 1..4) {
            assertEquals(
                "missing If-Modified-Since on request $index",
                RtspConstants.IF_MODIFIED_SINCE_VALUE,
                headerOf(transport.requests[index], RtspConstants.HEADER_IF_MODIFIED_SINCE),
            )
        }
        assertEquals(
            RtspConstants.MIME_SDP,
            headerOf(transport.requests[1], RtspConstants.HEADER_ACCEPT),
        )
        for (index in 2..4) {
            assertEquals(
                RtspConstants.TRANSPORT_REQUEST_VALUE,
                headerOf(transport.requests[index], RtspConstants.HEADER_TRANSPORT),
            )
        }
    }

    @Test
    fun `the transport is closed whether the handshake succeeds or fails`() {
        val good = FakeRtspTransport(responder = successResponder())
        negotiate(good)
        assertTrue(good.closeCount >= 1)

        val bad = FakeRtspTransport(responder = { FakeReply.Fail("boom") })
        negotiate(bad)
        assertTrue(bad.closeCount >= 1)
    }

    @Test
    fun `a response arriving one byte at a time still frames correctly`() {
        val transport = FakeRtspTransport(chunkSize = 1, responder = successResponder())
        val result = negotiate(transport)
        assertTrue(describe(result), result.isSuccess)
        assertEquals(48000, session(result).audioPort)
    }

    // ---- Generation branches ---------------------------------------------------------------------

    @Test
    fun `a Gen 5 host below 7 point 1 point 431 uses the legacy control id and announces to video`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val result = negotiate(transport, profile = SdpGoldens.nvidiaGen5())

        assertTrue(describe(result), result.isSuccess)
        assertEquals(
            listOf(
                "rtsp://192.168.1.50:48010",
                "rtsp://192.168.1.50:48010",
                "rtsp://192.168.1.50:48010/streamid=audio/0/0",
                "rtsp://192.168.1.50:48010/streamid=video/0/0",
                "rtsp://192.168.1.50:48010/streamid=control/1/0",
                "rtsp://192.168.1.50:48010/streamid=video",
                "rtsp://192.168.1.50:48010/streamid=video",
                "rtsp://192.168.1.50:48010/streamid=audio",
            ),
            transport.requests.map { it.target },
        )
    }

    @Test
    fun `a Gen 4 host performs no control SETUP at all`() {
        val profile = RtspHostProfile(AppVersion(listOf(4, 0, 0, 0)), ServerKind.UNKNOWN)
        val transport = FakeRtspTransport(responder = successResponder())
        val result = negotiate(transport, profile = profile)

        assertTrue(describe(result), result.isSuccess)
        assertEquals(7, transport.requests.size)
        assertEquals(
            listOf(
                "rtsp://192.168.1.50:48010",
                "rtsp://192.168.1.50:48010",
                "rtsp://192.168.1.50:48010/streamid=audio",
                "rtsp://192.168.1.50:48010/streamid=video",
                "rtsp://192.168.1.50:48010/streamid=video",
                "rtsp://192.168.1.50:48010/streamid=video",
                "rtsp://192.168.1.50:48010/streamid=audio",
            ),
            transport.requests.map { it.target },
        )
        val session = session(result)
        assertFalse(session.controlSetupPerformed)
        assertEquals(RtspConstants.DEFAULT_CONTROL_PORT, session.controlPort)
    }

    // ---- What the host tells us ------------------------------------------------------------------

    @Test
    fun `a SETUP without a usable Transport header falls back to the documented default ports`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                when {
                    request.method == RtspConstants.METHOD_SETUP &&
                        request.target.contains("audio") ->
                        FakeReply.Respond(ok(request, listOf(SESSION_HEADER)))
                    else -> successResponder()(request)
                }
            },
        )
        val session = session(negotiate(transport))
        assertEquals(RtspConstants.DEFAULT_AUDIO_PORT, session.audioPort)
        assertEquals(47998, session.videoPort)
    }

    @Test
    fun `a host that sends no Sunshine headers falls back to the legacy ping and zero connect data`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                when {
                    request.method != RtspConstants.METHOD_SETUP -> successResponder()(request)
                    request.target.contains("audio") -> FakeReply.Respond(
                        ok(request, listOf(SESSION_HEADER, "Transport" to "server_port=48000")),
                    )
                    request.target.contains("video") -> FakeReply.Respond(
                        ok(request, listOf("Transport" to "server_port=47998")),
                    )
                    else -> FakeReply.Respond(ok(request, listOf("Transport" to "server_port=47999")))
                }
            },
        )
        val session = session(negotiate(transport))
        assertNull(session.audioPingPayload)
        assertNull(session.videoPingPayload)
        assertEquals(0, session.controlConnectData)
    }

    @Test
    fun `surround is downgraded to stereo when the host offers no matching Opus configuration`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_DESCRIBE) {
                    FakeReply.Respond(ok(request, body = "v=0\r\ns=NVIDIA Streaming Server\r\n"))
                } else {
                    successResponder()(request)
                }
            },
        )
        val result = negotiate(
            transport,
            configuration = SdpGoldens.config1080p60H264Stereo()
                .copy(audioLayout = AudioChannelLayout.SURROUND_5_1),
        )

        val session = session(result)
        assertTrue(session.audioLayoutDowngraded)
        assertEquals(AudioChannelLayout.STEREO, session.audioLayout)
        assertArrayEquals(intArrayOf(0, 1), session.opusConfig.mapping)
        // …and the SDP we actually announced says stereo too, rather than promising surround.
        assertTrue(session.announcedSdp.contains("a=x-nv-audio.surround.numChannels:2\r\n"))
        assertTrue(session.announcedSdp.contains("a=x-nv-audio.surround.enable:0\r\n"))
    }

    @Test
    fun `a surround layout the host does offer survives intact`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val result = negotiate(
            transport,
            configuration = SdpGoldens.config4K60HdrSurround(),
        )

        val session = session(result)
        assertFalse(session.audioLayoutDowngraded)
        assertEquals(AudioChannelLayout.SURROUND_5_1, session.audioLayout)
        assertArrayEquals(intArrayOf(0, 1, 2, 5, 3, 4), session.opusConfig.mapping)
        assertEquals(SdpGoldens.SDP_4K60_HDR_HEVC_SURROUND51_SUNSHINE, session.announcedSdp)
    }

    @Test
    fun `an unknown attribute in the DESCRIBE body is ignored rather than fatal`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_DESCRIBE) {
                    FakeReply.Respond(
                        ok(request, body = DESCRIBE_SDP + "a=x-ss-brand-new.option:whatever\r\n"),
                    )
                } else {
                    successResponder()(request)
                }
            },
        )
        val result = negotiate(transport)
        assertTrue(describe(result), result.isSuccess)
        assertEquals(
            "whatever",
            session(result).hostDescription.attribute("x-ss-brand-new.option"),
        )
    }

    // ---- Error classification ---------------------------------------------------------------------

    @Test
    fun `a refusal names the step and carries the host's own status and text`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_OPTIONS) {
                    FakeReply.Respond("RTSP/1.0 500 Internal Server Error\r\nCSeq: 1\r\n\r\n")
                } else {
                    successResponder()(request)
                }
            },
        )
        val error = failure(negotiate(transport))
        assertTrue(describe(error), error is RtspError.Refused)
        error as RtspError.Refused
        assertEquals(RtspStep.OPTIONS, error.step)
        assertEquals(500, error.statusCode)
        assertEquals("Internal Server Error", error.reasonPhrase)
        assertTrue(error.describe().contains("OPTIONS"))
    }

    @Test
    fun `a rejected ANNOUNCE is a refusal at ANNOUNCE, which is what points at the configuration`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_ANNOUNCE) {
                    FakeReply.Respond("RTSP/1.0 400 Bad Request\r\nCSeq: 6\r\n\r\n")
                } else {
                    successResponder()(request)
                }
            },
        )
        val error = failure(negotiate(transport))
        assertTrue(describe(error), error is RtspError.Refused)
        error as RtspError.Refused
        assertEquals(RtspStep.ANNOUNCE, error.step)
        assertEquals(400, error.statusCode)
        // The three SETUPs did happen; the configuration is what the host disliked.
        assertEquals(6, transport.requests.size)
    }

    @Test
    fun `a silent host is a timeout, not a refusal`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_DESCRIBE) FakeReply.Timeout
                else successResponder()(request)
            },
        )
        val error = failure(negotiate(transport))
        assertTrue(describe(error), error is RtspError.Timeout)
        error as RtspError.Timeout
        assertEquals(RtspStep.DESCRIBE, error.step)
        assertEquals(RtspConstants.DESCRIBE_TIMEOUT_MS.toLong(), error.waitedMs)
        assertFalse(error.budgetExhausted)
    }

    @Test
    fun `an unparseable answer is malformed, not a timeout and not a refusal`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_DESCRIBE) {
                    FakeReply.Respond("this is not RTSP at all\r\n\r\n")
                } else {
                    successResponder()(request)
                }
            },
        )
        val error = failure(negotiate(transport))
        assertTrue(describe(error), error is RtspError.Malformed)
        assertEquals(RtspStep.DESCRIBE, error.step)
    }

    @Test
    fun `an audio SETUP with no Session header is malformed, because nothing after it can proceed`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_SETUP && request.target.contains("audio")) {
                    FakeReply.Respond(ok(request, listOf("Transport" to "server_port=48000")))
                } else {
                    successResponder()(request)
                }
            },
        )
        val error = failure(negotiate(transport))
        assertTrue(describe(error), error is RtspError.Malformed)
        assertEquals(RtspStep.SETUP_AUDIO, error.step)
        assertTrue(error.describe().contains("Session"))
    }

    @Test
    fun `a host that hangs up mid handshake is unreachable, not malformed`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_SETUP) FakeReply.CloseConnection
                else successResponder()(request)
            },
        )
        val error = failure(negotiate(transport))
        assertTrue(describe(error), error is RtspError.Unreachable)
        assertEquals(RtspStep.SETUP_AUDIO, error.step)
    }

    @Test
    fun `a broken socket mid handshake is unreachable`() {
        val transport = FakeRtspTransport(
            responder = { request ->
                if (request.method == RtspConstants.METHOD_PLAY) FakeReply.Fail("connection reset")
                else successResponder()(request)
            },
        )
        val error = failure(negotiate(transport))
        assertTrue(describe(error), error is RtspError.Unreachable)
        assertEquals(RtspStep.PLAY_VIDEO, error.step)
    }

    @Test
    fun `a refused connect and a connect timeout are told apart`() {
        val refused = FakeRtspTransport(responder = successResponder())
        refused.connectFailure = ConnectException("Connection refused")
        val refusedError = failure(negotiate(refused))
        assertTrue(describe(refusedError), refusedError is RtspError.Unreachable)
        assertEquals(RtspStep.CONNECT, refusedError.step)
        assertTrue(refused.requests.isEmpty())

        val slow = FakeRtspTransport(responder = successResponder())
        slow.connectFailure = SocketTimeoutException("connect timed out")
        val slowError = failure(negotiate(slow))
        assertTrue(describe(slowError), slowError is RtspError.Timeout)
        assertEquals(RtspStep.CONNECT, slowError.step)
        assertEquals(RtspConstants.CONNECT_TIMEOUT_MS.toLong(), (slowError as RtspError.Timeout).waitedMs)
    }

    @Test
    fun `a launch that never started is reported before any socket is opened`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val error = failure(
            negotiate(transport, launch = LaunchResponse(false, null, null, false)),
        )
        assertTrue(describe(error), error is RtspError.NotLaunched)
        assertEquals(RtspStep.LAUNCH, error.step)
        assertFalse(transport.connected)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `the whole handshake budget is enforced between steps`() {
        // Nothing for three calls, then far past the budget: the deadline check before DESCRIBE is
        // the one that fires, after OPTIONS has already gone out.
        var calls = 0
        val clock = {
            calls++
            if (calls <= 3) 0L else RtspConstants.SESSION_BUDGET_MS * 2_000_000L
        }
        val transport = FakeRtspTransport(responder = successResponder())
        val error = failure(negotiate(transport, clock = clock))

        assertTrue(describe(error), error is RtspError.Timeout)
        error as RtspError.Timeout
        assertTrue(error.budgetExhausted)
        assertEquals(RtspStep.DESCRIBE, error.step)
        assertEquals(RtspConstants.SESSION_BUDGET_MS, error.waitedMs)
        assertEquals(1, transport.requests.size)
    }

    // ---- Session URL handling ----------------------------------------------------------------------

    @Test
    fun `the RTSP port comes from sessionUrl0 and falls back to the default`() {
        val custom = FakeRtspTransport(responder = successResponder())
        val customResult = negotiate(
            custom,
            launch = LaunchResponse(true, "rtsp://192.168.1.50:49010", 49010, false),
        )
        assertEquals(49010, session(customResult).rtspPort)
        assertTrue(custom.requests[0].target.endsWith(":49010"))

        val fallback = FakeRtspTransport(responder = successResponder())
        val fallbackResult = negotiate(fallback, launch = LaunchResponse(true, null, null, false))
        assertEquals(48010, session(fallbackResult).rtspPort)
    }

    @Test
    fun `an rtspru session URL is still connected to over TCP`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val result = negotiate(
            transport,
            launch = LaunchResponse(true, "rtspru://192.168.1.50:48010", 48010, true),
        )
        assertTrue(describe(result), result.isSuccess)
        assertTrue(transport.requests[0].target.startsWith("rtsp://"))
    }

    @Test
    fun `an IPv6 host is bracketed in the request target and bare in the SDP origin`() {
        val transport = FakeRtspTransport(responder = successResponder())
        val result = runBlocking {
            RtspSessionNegotiator({ _, _ -> transport }, { 0L }).negotiate(
                RtspSessionRequest(
                    host = "[fe80::1]",
                    launch = LaunchResponse(true, null, null, false),
                    profile = SdpGoldens.sunshineGen7(),
                    configuration = SdpGoldens.config1080p60H264Stereo(),
                ),
            )
        }
        assertTrue(describe(result), result.isSuccess)
        assertEquals("rtsp://[fe80::1]:48010", transport.requests[0].target)
        assertTrue(session(result).announcedSdp.contains("o=android 0 14 IN IP6 fe80::1\r\n"))
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private fun negotiate(
        transport: FakeRtspTransport,
        profile: RtspHostProfile = SdpGoldens.sunshineGen7(),
        configuration: StreamConfiguration = SdpGoldens.config1080p60H264Stereo(),
        launch: LaunchResponse = LaunchResponse(true, "rtsp://192.168.1.50:48010", 48010, false),
        clock: () -> Long = { 0L },
    ): RtspSessionResult = runBlocking {
        RtspSessionNegotiator({ _, _ -> transport }, clock).negotiate(
            RtspSessionRequest(SdpGoldens.HOST, launch, profile, configuration),
        )
    }

    private fun session(result: RtspSessionResult): NegotiatedSession {
        assertTrue(describe(result), result is RtspSessionResult.Success)
        return (result as RtspSessionResult.Success).session
    }

    private fun failure(result: RtspSessionResult): RtspError {
        assertTrue("expected a failure, got a session", result is RtspSessionResult.Failure)
        return (result as RtspSessionResult.Failure).error
    }

    private fun describe(result: RtspSessionResult): String = when (result) {
        is RtspSessionResult.Success -> "unexpected success: ${result.session}"
        is RtspSessionResult.Failure -> result.error.describe()
    }

    private fun describe(error: RtspError): String =
        "${error.javaClass.simpleName}: ${error.describe()}"

    private fun headerOf(request: RtspRequest, name: String): String? =
        request.headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private companion object {

        const val AUDIO_PING: String = "0123456789abcdef"
        const val VIDEO_PING: String = "fedcba9876543210"

        val SESSION_HEADER: Pair<String, String> =
            RtspConstants.HEADER_SESSION to "DEADBEEFCAFE;timeout=90"

        /** A DESCRIBE body with Opus runs for both surround layouts and an attribute we consume. */
        val DESCRIBE_SDP: String = listOf(
            "v=0",
            "o=- 0 0 IN IP4 192.168.1.50",
            "s=NVIDIA Streaming Server",
            "a=x-nv-general.featureFlags:1",
            "a=fmtp:97 surround-params=642012345",
            "a=fmtp:97 surround-params=85301234567",
            "t=0 0",
            "m=video 47998  ",
        ).joinToString(separator = "") { it + "\r\n" }

        /** A `200 OK` echoing the request's own `CSeq`, as a real host does. */
        fun ok(
            request: RtspRequest,
            headers: List<Pair<String, String>> = emptyList(),
            body: String? = null,
        ): String {
            val builder = StringBuilder(256)
            builder.append("RTSP/1.0 200 OK\r\n")
            builder.append("CSeq: ").append(request.cseq ?: 0).append("\r\n")
            for ((name, value) in headers) {
                builder.append(name).append(": ").append(value).append("\r\n")
            }
            if (body != null) {
                builder.append("Content-length: ")
                    .append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
            }
            builder.append("\r\n")
            if (body != null) builder.append(body)
            return builder.toString()
        }

        /** A fake Sunshine host that answers every step of the handshake correctly. */
        fun successResponder(): (RtspRequest) -> FakeReply = { request ->
            when {
                request.method == RtspConstants.METHOD_OPTIONS -> FakeReply.Respond(ok(request))

                request.method == RtspConstants.METHOD_DESCRIBE ->
                    FakeReply.Respond(ok(request, body = DESCRIBE_SDP))

                request.method == RtspConstants.METHOD_SETUP && request.target.contains("audio") ->
                    FakeReply.Respond(
                        ok(
                            request,
                            listOf(
                                SESSION_HEADER,
                                RtspConstants.HEADER_TRANSPORT to "unicast;server_port=48000-48001",
                                RtspConstants.HEADER_SS_PING_PAYLOAD to AUDIO_PING,
                            ),
                        ),
                    )

                request.method == RtspConstants.METHOD_SETUP && request.target.contains("video") ->
                    FakeReply.Respond(
                        ok(
                            request,
                            listOf(
                                RtspConstants.HEADER_TRANSPORT to "unicast;server_port=47998-47999",
                                RtspConstants.HEADER_SS_PING_PAYLOAD to VIDEO_PING,
                            ),
                        ),
                    )

                request.method == RtspConstants.METHOD_SETUP ->
                    FakeReply.Respond(
                        ok(
                            request,
                            listOf(
                                RtspConstants.HEADER_TRANSPORT to "unicast;server_port=47999",
                                RtspConstants.HEADER_SS_CONNECT_DATA to "0x2A",
                            ),
                        ),
                    )

                request.method == RtspConstants.METHOD_ANNOUNCE -> FakeReply.Respond(ok(request))
                request.method == RtspConstants.METHOD_PLAY -> FakeReply.Respond(ok(request))
                else -> FakeReply.Fail("the fake host does not know ${request.method}")
            }
        }
    }

    /** Kept so an unused-import warning cannot hide a genuinely unused failure mode. */
    @Test
    fun `an IOException from the transport is classified rather than escaping`() {
        val transport = object : RtspTransport {
            override suspend fun connect(timeoutMs: Int) = Unit
            override suspend fun write(bytes: ByteArray, timeoutMs: Int) {
                throw IOException("write failed")
            }

            override suspend fun read(destination: ByteArray, timeoutMs: Int): Int = -1
            override fun close() = Unit
        }
        val result = runBlocking {
            RtspSessionNegotiator({ _, _ -> transport }, { 0L }).negotiate(
                RtspSessionRequest(
                    host = SdpGoldens.HOST,
                    launch = LaunchResponse(true, null, null, false),
                    profile = SdpGoldens.sunshineGen7(),
                    configuration = SdpGoldens.config1080p60H264Stereo(),
                ),
            )
        }
        val error = failure(result)
        assertTrue(describe(error), error is RtspError.Unreachable)
        assertEquals(RtspStep.OPTIONS, error.step)
    }
}
