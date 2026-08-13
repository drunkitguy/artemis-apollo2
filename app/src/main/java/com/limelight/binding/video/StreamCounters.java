package com.limelight.binding.video;

/**
 * An immutable snapshot of the cumulative video counters the renderer already keeps for
 * the performance overlay.
 *
 * It lives in this package so it can be built straight from {@link VideoStats}, whose
 * fields are package private. Nothing here is Android specific, and nothing here changes
 * how the stream runs -- it is a read-only view for callers (the bitrate test) that want
 * to difference two points in time.
 */
public final class StreamCounters {

    public final long totalFrames;
    public final long framesReceived;
    public final long framesRendered;
    public final long framesLost;
    public final long frameLossEvents;
    public final long decoderTimeMs;
    public final long hostProcessingLatencyTenthsMs;
    public final long framesWithHostProcessingLatency;

    public StreamCounters(long totalFrames,
                          long framesReceived,
                          long framesRendered,
                          long framesLost,
                          long frameLossEvents,
                          long decoderTimeMs,
                          long hostProcessingLatencyTenthsMs,
                          long framesWithHostProcessingLatency) {
        this.totalFrames = totalFrames;
        this.framesReceived = framesReceived;
        this.framesRendered = framesRendered;
        this.framesLost = framesLost;
        this.frameLossEvents = frameLossEvents;
        this.decoderTimeMs = decoderTimeMs;
        this.hostProcessingLatencyTenthsMs = hostProcessingLatencyTenthsMs;
        this.framesWithHostProcessingLatency = framesWithHostProcessingLatency;
    }

    /** This snapshot minus an earlier one, clamped at zero. */
    public StreamCounters minus(StreamCounters earlier) {
        if (earlier == null) {
            return this;
        }
        return new StreamCounters(
                Math.max(0, totalFrames - earlier.totalFrames),
                Math.max(0, framesReceived - earlier.framesReceived),
                Math.max(0, framesRendered - earlier.framesRendered),
                Math.max(0, framesLost - earlier.framesLost),
                Math.max(0, frameLossEvents - earlier.frameLossEvents),
                Math.max(0, decoderTimeMs - earlier.decoderTimeMs),
                Math.max(0, hostProcessingLatencyTenthsMs - earlier.hostProcessingLatencyTenthsMs),
                Math.max(0, framesWithHostProcessingLatency - earlier.framesWithHostProcessingLatency));
    }
}
