package com.limelight.nvstream.jni;

import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

public class MoonBridge {
    /* See documentation in Limelight.h for information about these functions and constants */

    public static final AudioConfiguration AUDIO_CONFIGURATION_STEREO = new AudioConfiguration(2, 0x3);
    public static final AudioConfiguration AUDIO_CONFIGURATION_51_SURROUND = new AudioConfiguration(6, 0x3F);
    public static final AudioConfiguration AUDIO_CONFIGURATION_71_SURROUND = new AudioConfiguration(8, 0x63F);

    public static final int VIDEO_FORMAT_H264 = 0x0001;
    public static final int VIDEO_FORMAT_H265 = 0x0100;
    public static final int VIDEO_FORMAT_H265_MAIN10 = 0x0200;
    public static final int VIDEO_FORMAT_AV1_MAIN8 = 0x1000;
    public static final int VIDEO_FORMAT_AV1_MAIN10 = 0x2000;

    public static final int VIDEO_FORMAT_MASK_H264 = 0x000F;
    public static final int VIDEO_FORMAT_MASK_H265 = 0x0F00;
    public static final int VIDEO_FORMAT_MASK_AV1 = 0xF000;
    public static final int VIDEO_FORMAT_MASK_10BIT = 0x2200;

    public static final int BUFFER_TYPE_PICDATA = 0;
    public static final int BUFFER_TYPE_SPS = 1;
    public static final int BUFFER_TYPE_PPS = 2;
    public static final int BUFFER_TYPE_VPS = 3;

    public static final int FRAME_TYPE_PFRAME = 0;
    public static final int FRAME_TYPE_IDR = 1;

    public static final int COLORSPACE_REC_601 = 0;
    public static final int COLORSPACE_REC_709 = 1;
    public static final int COLORSPACE_REC_2020 = 2;

    public static final int COLOR_RANGE_LIMITED = 0;
    public static final int COLOR_RANGE_FULL = 1;

    public static final int CAPABILITY_DIRECT_SUBMIT = 1;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC = 2;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC = 4;
    public static final int CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1 = 0x40;

    public static final int DR_OK = 0;
    public static final int DR_NEED_IDR = -1;

    public static final int CONN_STATUS_OKAY = 0;
    public static final int CONN_STATUS_POOR = 1;

    public static final int ML_ERROR_GRACEFUL_TERMINATION = 0;
    public static final int ML_ERROR_NO_VIDEO_TRAFFIC = -100;
    public static final int ML_ERROR_NO_VIDEO_FRAME = -101;
    public static final int ML_ERROR_UNEXPECTED_EARLY_TERMINATION = -102;
    public static final int ML_ERROR_PROTECTED_CONTENT = -103;
    public static final int ML_ERROR_FRAME_CONVERSION = -104;

    public static final int ML_PORT_INDEX_TCP_47984 = 0;
    public static final int ML_PORT_INDEX_TCP_47989 = 1;
    public static final int ML_PORT_INDEX_TCP_48010 = 2;
    public static final int ML_PORT_INDEX_UDP_47998 = 8;
    public static final int ML_PORT_INDEX_UDP_47999 = 9;
    public static final int ML_PORT_INDEX_UDP_48000 = 10;
    public static final int ML_PORT_INDEX_UDP_48010 = 11;

    public static final int ML_PORT_FLAG_ALL = 0xFFFFFFFF;
    public static final int ML_PORT_FLAG_TCP_47984 = 0x0001;
    public static final int ML_PORT_FLAG_TCP_47989 = 0x0002;
    public static final int ML_PORT_FLAG_TCP_48010 = 0x0004;
    public static final int ML_PORT_FLAG_UDP_47998 = 0x0100;
    public static final int ML_PORT_FLAG_UDP_47999 = 0x0200;
    public static final int ML_PORT_FLAG_UDP_48000 = 0x0400;
    public static final int ML_PORT_FLAG_UDP_48010 = 0x0800;

    public static final int ML_TEST_RESULT_INCONCLUSIVE = 0xFFFFFFFF;

    public static final byte SS_KBE_FLAG_NON_NORMALIZED = 0x01;

    public static final int LI_ERR_UNSUPPORTED = -5501;

    public static final byte LI_TOUCH_EVENT_HOVER       = 0x00;
    public static final byte LI_TOUCH_EVENT_DOWN        = 0x01;
    public static final byte LI_TOUCH_EVENT_UP          = 0x02;
    public static final byte LI_TOUCH_EVENT_MOVE        = 0x03;
    public static final byte LI_TOUCH_EVENT_CANCEL      = 0x04;
    public static final byte LI_TOUCH_EVENT_BUTTON_ONLY = 0x05;
    public static final byte LI_TOUCH_EVENT_HOVER_LEAVE = 0x06;
    public static final byte LI_TOUCH_EVENT_CANCEL_ALL  = 0x07;

    public static final byte LI_TOOL_TYPE_UNKNOWN = 0x00;
    public static final byte LI_TOOL_TYPE_PEN = 0x01;
    public static final byte LI_TOOL_TYPE_ERASER = 0x02;

    public static final byte LI_PEN_BUTTON_PRIMARY = 0x01;
    public static final byte LI_PEN_BUTTON_SECONDARY = 0x02;
    public static final byte LI_PEN_BUTTON_TERTIARY = 0x04;

    public static final byte LI_TILT_UNKNOWN = (byte)0xFF;
    public static final short LI_ROT_UNKNOWN = (short)0xFFFF;

    public static final byte LI_CTYPE_UNKNOWN  = 0x00;
    public static final byte LI_CTYPE_XBOX     = 0x01;
    public static final byte LI_CTYPE_PS       = 0x02;
    public static final byte LI_CTYPE_NINTENDO = 0x03;

    public static final short LI_CCAP_ANALOG_TRIGGERS = 0x01;
    public static final short LI_CCAP_RUMBLE          = 0x02;
    public static final short LI_CCAP_TRIGGER_RUMBLE  = 0x04;
    public static final short LI_CCAP_TOUCHPAD        = 0x08;
    public static final short LI_CCAP_ACCEL           = 0x10;
    public static final short LI_CCAP_GYRO            = 0x20;
    public static final short LI_CCAP_BATTERY_STATE   = 0x40;
    public static final short LI_CCAP_RGB_LED         = 0x80;

    public static final byte LI_MOTION_TYPE_ACCEL = 0x01;
    public static final byte LI_MOTION_TYPE_GYRO  = 0x02;

    public static final byte LI_BATTERY_STATE_UNKNOWN      = 0x00;
    public static final byte LI_BATTERY_STATE_NOT_PRESENT  = 0x01;
    public static final byte LI_BATTERY_STATE_DISCHARGING  = 0x02;
    public static final byte LI_BATTERY_STATE_CHARGING     = 0x03;
    public static final byte LI_BATTERY_STATE_NOT_CHARGING = 0x04; // Connected to power but not charging
    public static final byte LI_BATTERY_STATE_FULL         = 0x05;

    public static final byte LI_BATTERY_PERCENTAGE_UNKNOWN = (byte)0xFF;

    private static AudioRenderer audioRenderer;
    private static VideoDecoderRenderer videoRenderer;
    private static NvConnectionListener connectionListener;

    static {
        System.loadLibrary("moonlight-core");
        init();
    }

    public static int CAPABILITY_SLICES_PER_FRAME(byte slices) {
        return slices << 24;
    }

    public static class AudioConfiguration {
        public final int channelCount;
        public final int channelMask;

        public AudioConfiguration(int channelCount, int channelMask) {
            this.channelCount = channelCount;
            this.channelMask = channelMask;
        }

        // Creates an AudioConfiguration from the integer value returned by moonlight-common-c
        // See CHANNEL_COUNT_FROM_AUDIO_CONFIGURATION() and CHANNEL_MASK_FROM_AUDIO_CONFIGURATION()
        // in Limelight.h
        private AudioConfiguration(int audioConfiguration) {
            // Check the magic byte before decoding to make sure we got something that's actually
            // a MAKE_AUDIO_CONFIGURATION()-based value and not something else like an older version
            // hardcoded AUDIO_CONFIGURATION value from an earlier version of moonlight-common-c.
            if ((audioConfiguration & 0xFF) != 0xCA) {
                throw new IllegalArgumentException("Audio configuration has invalid magic byte!");
            }

            this.channelCount = (audioConfiguration >> 8) & 0xFF;
            this.channelMask = (audioConfiguration >> 16) & 0xFFFF;
        }

        // See SURROUNDAUDIOINFO_FROM_AUDIO_CONFIGURATION() in Limelight.h
        public int getSurroundAudioInfo() {
            return channelMask << 16 | channelCount;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof AudioConfiguration) {
                AudioConfiguration that = (AudioConfiguration)obj;
                return this.toInt() == that.toInt();
            }

            return false;
        }

        @Override
        public int hashCode() {
            return toInt();
        }

        // Returns the integer value expected by moonlight-common-c
        // See MAKE_AUDIO_CONFIGURATION() in Limelight.h
        public int toInt() {
            return ((channelMask) << 16) | (channelCount << 8) | 0xCA;
        }
    }

    public static int bridgeDrSetup(int videoFormat, int width, int height, int redrawRate) {
        if (videoRenderer != null) {
            return videoRenderer.setup(videoFormat, width, height, redrawRate);
        }
        else {
            return -1;
        }
    }

    public static void bridgeDrStart() {
        if (videoRenderer != null) {
            videoRenderer.start();
        }
    }

    public static void bridgeDrStop() {
        if (videoRenderer != null) {
            videoRenderer.stop();
        }
    }

    public static void bridgeDrCleanup() {
        if (videoRenderer != null) {
            videoRenderer.cleanup();
        }
    }

    //todo 不显示画面
    public static int bridgeDrSubmitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                               int frameNumber, int frameType, char frameHostProcessingLatency,
                                               long receiveTimeMs, long enqueueTimeMs,
                                               int traceFlags, long traceLastPacketRxUs,
                                               long traceHostCaptureRequestedUs, long traceHostCaptureCompleteUs,
                                               long traceHostEncodeSubmitUs, long traceHostEncodeCompleteUs,
                                               long traceHostTxPipelineEntryUs,
                                               int lateFrameToleranceUs) {
        if (videoRenderer != null) {
            // Zero unless the adaptive late-frame tolerance is enabled, so a
            // stock session just stores a zero the renderer never applies.
            videoRenderer.setLateFrameToleranceUs(lateFrameToleranceUs);

            // Native invokes this callback once per parameter set NALU and once
            // for the picture data, so gate the trace on the picture data call to
            // get exactly one row per frame. The trace arguments are all zero
            // unless the latency trace was negotiated, so this stays a no-op for
            // stock sessions.
            if (decodeUnitType == BUFFER_TYPE_PICDATA) {
                videoRenderer.submitTraceTimestamps(frameNumber, traceFlags, traceLastPacketRxUs,
                        traceHostCaptureRequestedUs, traceHostCaptureCompleteUs,
                        traceHostEncodeSubmitUs, traceHostEncodeCompleteUs, traceHostTxPipelineEntryUs);
            }
            return videoRenderer.submitDecodeUnit(decodeUnitData, decodeUnitLength,
                    decodeUnitType, frameNumber, frameType, frameHostProcessingLatency, receiveTimeMs, enqueueTimeMs);
        }
        else {
            return DR_OK;
        }
    }

    public static int bridgeArInit(int audioConfiguration, int sampleRate, int samplesPerFrame) {
        if (audioRenderer != null) {
            return audioRenderer.setup(new AudioConfiguration(audioConfiguration), sampleRate, samplesPerFrame);
        }
        else {
            return -1;
        }
    }

    public static void bridgeArStart() {
        if (audioRenderer != null) {
            audioRenderer.start();
        }
    }

    public static void bridgeArStop() {
        if (audioRenderer != null) {
            audioRenderer.stop();
        }
    }

    public static void bridgeArCleanup() {
        if (audioRenderer != null) {
            audioRenderer.cleanup();
        }
    }

    //静音 todo
    public static void bridgeArPlaySample(short[] pcmData) {
        if (audioRenderer != null) {
            audioRenderer.playDecodedAudio(pcmData);
        }
    }

    public static void bridgeClStageStarting(int stage) {
        if (connectionListener != null) {
            connectionListener.stageStarting(getStageName(stage));
        }
    }

    public static void bridgeClStageComplete(int stage) {
        if (connectionListener != null) {
            connectionListener.stageComplete(getStageName(stage));
        }
    }

    public static void bridgeClStageFailed(int stage, int errorCode) {
        if (connectionListener != null) {
            connectionListener.stageFailed(getStageName(stage), getPortFlagsFromStage(stage), errorCode);
        }
    }

    public static void bridgeClConnectionStarted() {
        if (connectionListener != null) {
            connectionListener.connectionStarted();
        }
    }

    public static void bridgeClConnectionTerminated(int errorCode) {
        if (connectionListener != null) {
            connectionListener.connectionTerminated(errorCode);
        }
    }

    public static void bridgeClRumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        if (connectionListener != null) {
            connectionListener.rumble(controllerNumber, lowFreqMotor, highFreqMotor);
        }
    }

    public static void bridgeClConnectionStatusUpdate(int connectionStatus) {
        if (connectionListener != null) {
            connectionListener.connectionStatusUpdate(connectionStatus);
        }
    }

    public static void bridgeClSetHdrMode(boolean enabled, byte[] hdrMetadata) {
        if (connectionListener != null) {
            connectionListener.setHdrMode(enabled, hdrMetadata);
        }
    }

    public static void bridgeClRumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        if (connectionListener != null) {
            connectionListener.rumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
        }
    }

    public static void bridgeClSetMotionEventState(short controllerNumber, byte eventType, short sampleRateHz) {
        if (connectionListener != null) {
            connectionListener.setMotionEventState(controllerNumber, eventType, sampleRateHz);
        }
    }

    // Text input focus hint types. These mirror LI_TEXT_FOCUS_* in Limelight.h.
    public static final byte TEXT_FOCUS_NONE = 0;
    public static final byte TEXT_FOCUS_TEXT = 1;
    public static final byte TEXT_FOCUS_NUMERIC = 2;
    public static final byte TEXT_FOCUS_PASSWORD = 3;

    public static void bridgeClSetTextFocus(byte focusType) {
        if (connectionListener != null) {
            connectionListener.setTextFocus(focusType);
        }
    }

    public static void bridgeClSetControllerLED(short controllerNumber, byte r, byte g, byte b) {
        if (connectionListener != null) {
            connectionListener.setControllerLED(controllerNumber, r, g, b);
        }
    }

    public static void setupBridge(VideoDecoderRenderer videoRenderer, AudioRenderer audioRenderer, NvConnectionListener connectionListener) {
        MoonBridge.videoRenderer = videoRenderer;
        MoonBridge.audioRenderer = audioRenderer;
        MoonBridge.connectionListener = connectionListener;
    }

    public static void cleanupBridge() {
        MoonBridge.videoRenderer = null;
        MoonBridge.audioRenderer = null;
        MoonBridge.connectionListener = null;
    }

    public static native int startConnection(String address, String appVersion, String gfeVersion,
                                              String rtspSessionUrl, int serverCodecModeSupport,
                                              int width, int height, int fps,
                                              int bitrate, int packetSize, int streamingRemotely,
                                              int audioConfiguration, int supportedVideoFormats,
                                              int clientRefreshRateX100,
                                              byte[] riAesKey, byte[] riAesIv,
                                              int videoCapabilities,
                                              int colorSpace, int colorRange,
                                              int latencyTraceEnabled,
                                              int inputBatchingIntervalMs,
                                              int adaptiveLateFrameToleranceMaxMs,
                                              int textFocusEnabled,
                                              int inputProbeEnabled);

    public static native void stopConnection();

    public static native void interruptConnection();

    public static native void sendExecServerCmd(int cmdId);

    public static native void sendEmptyPayload();

    // Input round-trip probe. Call immediately before the matching send, on the
    // same thread. Returns after one branch when the probe is not negotiated.
    public static native void setNextInputEventTime(long eventTimeUs);

    // Drains probe samples into a flat long array, 8 longs per sample. Returns
    // the number of samples written.
    public static native int drainInputProbes(long[] out);

    // {sent, rateLimited, echoesMatched, conversionFailures}
    public static native int[] getInputProbeStats();

    public static native void sendMouseMove(short deltaX, short deltaY);

    public static native void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight);

    public static native void sendMouseMoveAsMousePosition(short deltaX, short deltaY, short referenceWidth, short referenceHeight);

    public static native void sendMouseButton(byte buttonEvent, byte mouseButton);

    public static native void sendMultiControllerInput(short controllerNumber,
                                    short activeGamepadMask, int buttonFlags,
                                    byte leftTrigger, byte rightTrigger,
                                    short leftStickX, short leftStickY,
                                    short rightStickX, short rightStickY);

    public static native int sendTouchEvent(byte eventType, int pointerId, float x, float y, float pressure,
                                            float contactAreaMajor, float contactAreaMinor, short rotation);

    public static native int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                                          float pressure, float contactAreaMajor, float contactAreaMinor,
                                          short rotation, byte tilt);

    public static native int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask, byte type, int supportedButtonFlags, short capabilities);

    public static native int sendControllerTouchEvent(byte controllerNumber, byte eventType, int pointerId, float x, float y, float pressure);

    public static native int sendControllerMotionEvent(byte controllerNumber, byte motionType, float x, float y, float z);

    public static native int sendControllerBatteryEvent(byte controllerNumber, byte batteryState, byte batteryPercentage);

    public static native void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags);

    public static native void sendMouseHighResScroll(short scrollAmount);

    public static native void sendMouseHighResHScroll(short scrollAmount);

    public static native void sendUtf8Text(String text);

    public static native String getStageName(int stage);

    public static native String findExternalAddressIP4(String stunHostName, int stunPort);

    public static native int getPendingAudioDuration();

    public static native int getPendingVideoFrames();

    public static native int testClientConnectivity(String testServerHostName, int referencePort, int testFlags);

    public static native int getPortFlagsFromStage(int stage);

    public static native int getPortFlagsFromTerminationErrorCode(int errorCode);

    public static native String stringifyPortFlags(int portFlags, String separator);

    // The RTT is in the top 32 bits, and the RTT variance is in the bottom 32 bits
    public static native long getEstimatedRttInfo();

    // Indexes into the array filled by getClockSyncInfo()
    public static final int CLOCK_SYNC_OFFSET_US = 0;
    public static final int CLOCK_SYNC_BEST_RTT_US = 1;
    public static final int CLOCK_SYNC_SAMPLE_COUNT = 2;
    public static final int CLOCK_SYNC_DIVERGENCE_EVENTS = 3;
    public static final int CLOCK_SYNC_VALID = 4;
    public static final int CLOCK_SYNC_UNMATCHED_RESPONSES = 5;
    public static final int CLOCK_SYNC_ARRAY_LENGTH = 6;

    // Fills out (which must be at least CLOCK_SYNC_ARRAY_LENGTH long) with the
    // latency trace clock offset state. Returns false only if the array is too
    // small. Pass a reused array; this allocates nothing.
    public static native boolean getClockSyncInfo(long[] out);

    // True only if the per-frame latency trace was negotiated with the host.
    public static native boolean getLatencyTraceEnabled();

    // Frame timestamp extension version observed on the wire this session, or 0
    // if none was parsed — which is also what you get when the host declined the
    // capability or is emitting a version this client does not understand.
    public static native int getFrameTraceExtVersion();

    // Bits in the traceFlags argument of bridgeDrSubmitDecodeUnit, and in
    // LatencyTraceRecorder's per-row flags for the host stamps.
    public static final int TRACE_FLAG_HOST_VALID = 0x01;
    public static final int TRACE_FLAG_LAST_PACKET_RX_VALID = 0x02;
    // Host per-stage validity mask, shifted up by 8. See SS_STAMP_VALID_* in Video.h.
    public static final int TRACE_HOST_STAMP_MASK_SHIFT = 8;
    public static final int TRACE_STAMP_CAPTURE_REQUESTED = 0x01;
    public static final int TRACE_STAMP_CAPTURE_COMPLETE = 0x02;
    public static final int TRACE_STAMP_ENCODE_SUBMIT = 0x04;
    public static final int TRACE_STAMP_ENCODE_COMPLETE = 0x08;
    public static final int TRACE_STAMP_TX_PIPELINE_ENTRY = 0x10;

    // Monotonic microseconds on the same epoch as the native trace timestamps.
    public static native long getMonotonicMicros();

    public static native String getLaunchUrlQueryParameters();

    public static native byte guessControllerType(int vendorId, int productId);

    public static native boolean guessControllerHasPaddles(int vendorId, int productId);

    public static native boolean guessControllerHasShareButton(int vendorId, int productId);

    public static native void init();
}
