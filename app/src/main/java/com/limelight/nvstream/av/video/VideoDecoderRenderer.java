package com.limelight.nvstream.av.video;

public abstract class VideoDecoderRenderer {
    public abstract int setup(int format, int width, int height, int redrawRate);

    public abstract void start();

    public abstract void stop();

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    public abstract int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                         int frameNumber, int frameType, char frameHostProcessingLatency,
                                         long receiveTimeMs, long enqueueTimeMs);
    
    // Extra lateness in microseconds that the renderer should tolerate before
    // discarding a frame as stale (SPEC.md §4 Item C). NOT a presentation delay:
    // nothing is delayed. Always zero unless the feature is enabled, so the
    // default implementation ignoring it is exactly stock behaviour.
    public void setLateFrameToleranceUs(int lateFrameToleranceUs) {
    }

    public abstract void cleanup();

    public abstract int getCapabilities();

    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
