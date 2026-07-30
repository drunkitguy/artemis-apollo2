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
    
    // Delivers the host half of the per-frame latency trace, immediately before the
    // submitDecodeUnit() calls for the same frame. All timestamps are monotonic
    // microseconds already converted to the client's clock epoch by the native
    // layer. traceFlags bit 0 indicates the host timestamps are valid; the host
    // fields are meaningless when it is clear, but traceLastPacketRxUs is still
    // usable because it needs no clock offset.
    //
    // Every argument is zero unless the trace was negotiated, so the default
    // implementation ignoring them costs nothing on a stock session.
    public void submitTraceTimestamps(int frameNumber, int traceFlags, long traceLastPacketRxUs,
                                      long traceHostCaptureRequestedUs, long traceHostCaptureCompleteUs,
                                      long traceHostEncodeSubmitUs, long traceHostEncodeCompleteUs,
                                      long traceHostTxPipelineEntryUs) {
    }

    public abstract void cleanup();

    public abstract int getCapabilities();

    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
