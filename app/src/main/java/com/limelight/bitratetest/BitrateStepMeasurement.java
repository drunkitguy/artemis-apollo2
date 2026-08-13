package com.limelight.bitratetest;

/**
 * What one rung of the ladder measured.
 *
 * The raw numbers are exactly the counters Artemis already keeps for the performance
 * overlay (see MediaCodecDecoderRenderer / VideoStats): frames received, frames lost,
 * accumulated decode time, and accumulated host processing latency. They are captured as
 * a delta across the measured part of the step, so the settling period right after the
 * encoder starts is excluded.
 *
 * Pure Java: no Android types, so this is unit testable.
 */
public final class BitrateStepMeasurement {

    private final int bitrateKbps;
    private final long framesReceived;
    private final long framesLost;
    private final long frameLossEvents;
    private final long decoderTimeMs;
    private final long hostProcessingLatencyTenthsMs;
    private final long framesWithHostProcessingLatency;
    private final long receivedBytes;
    private final long durationMs;
    private final String failureReason;

    private BitrateStepMeasurement(int bitrateKbps,
                                   long framesReceived,
                                   long framesLost,
                                   long frameLossEvents,
                                   long decoderTimeMs,
                                   long hostProcessingLatencyTenthsMs,
                                   long framesWithHostProcessingLatency,
                                   long receivedBytes,
                                   long durationMs,
                                   String failureReason) {
        this.bitrateKbps = bitrateKbps;
        this.framesReceived = framesReceived;
        this.framesLost = framesLost;
        this.frameLossEvents = frameLossEvents;
        this.decoderTimeMs = decoderTimeMs;
        this.hostProcessingLatencyTenthsMs = hostProcessingLatencyTenthsMs;
        this.framesWithHostProcessingLatency = framesWithHostProcessingLatency;
        this.receivedBytes = receivedBytes;
        this.durationMs = durationMs;
        this.failureReason = failureReason;
    }

    /**
     * A step that ran to completion.
     *
     * @param receivedBytes bytes received during the measured window, or a negative value
     *                      if the platform could not report it
     */
    public static BitrateStepMeasurement measured(int bitrateKbps,
                                                  long framesReceived,
                                                  long framesLost,
                                                  long frameLossEvents,
                                                  long decoderTimeMs,
                                                  long hostProcessingLatencyTenthsMs,
                                                  long framesWithHostProcessingLatency,
                                                  long receivedBytes,
                                                  long durationMs) {
        return new BitrateStepMeasurement(bitrateKbps, framesReceived, framesLost, frameLossEvents,
                decoderTimeMs, hostProcessingLatencyTenthsMs, framesWithHostProcessingLatency,
                receivedBytes, durationMs, null);
    }

    /** A step whose session never came up, or died before it could be measured. */
    public static BitrateStepMeasurement failed(int bitrateKbps, String reason) {
        return new BitrateStepMeasurement(bitrateKbps, 0, 0, 0, 0, 0, 0, -1, 0,
                reason == null ? "The stream did not survive at this bitrate." : reason);
    }

    public int getBitrateKbps() {
        return bitrateKbps;
    }

    public boolean isFailed() {
        return failureReason != null;
    }

    /** Null unless {@link #isFailed()}. */
    public String getFailureReason() {
        return failureReason;
    }

    public long getFramesReceived() {
        return framesReceived;
    }

    public long getFramesLost() {
        return framesLost;
    }

    public long getFrameLossEvents() {
        return frameLossEvents;
    }

    public long getDurationMs() {
        return durationMs;
    }

    /** Frames the host said it sent: the ones that arrived plus the ones that did not. */
    public long getTotalFrames() {
        return framesReceived + framesLost;
    }

    /** True when the step produced enough video to say anything about it. */
    public boolean hasVideo() {
        return !isFailed() && framesReceived > 0;
    }

    /**
     * Percentage of frames that never arrived. This is the same figure the performance
     * overlay labels as network packet loss.
     */
    public double getFrameLossPercent() {
        long total = getTotalFrames();
        if (total <= 0) {
            return 0;
        }
        return 100.0 * framesLost / total;
    }

    /** Mean time the local decoder spent per received frame, in ms. */
    public double getAverageDecodeTimeMs() {
        if (framesReceived <= 0) {
            return 0;
        }
        return (double) decoderTimeMs / framesReceived;
    }

    /** True when the host reported any processing latency at all for this step. */
    public boolean hasHostProcessingLatency() {
        return framesWithHostProcessingLatency > 0;
    }

    /**
     * Mean host (capture + encode) processing latency, in ms. The wire format is tenths
     * of a millisecond, matching the perf overlay.
     */
    public double getAverageHostProcessingLatencyMs() {
        if (framesWithHostProcessingLatency <= 0) {
            return 0;
        }
        return (double) hostProcessingLatencyTenthsMs / 10.0 / framesWithHostProcessingLatency;
    }

    /** Bitrate actually received during the step in kbps, or -1 when unavailable. */
    public int getReceivedKbps() {
        if (receivedBytes < 0 || durationMs <= 0) {
            return -1;
        }
        // bytes * 8 bits / ms == kbit/s
        return (int) (receivedBytes * 8 / durationMs);
    }
}
