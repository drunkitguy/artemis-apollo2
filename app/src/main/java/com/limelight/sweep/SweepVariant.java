package com.limelight.sweep;

/**
 * One configuration the sweep will connect with and measure.
 *
 * Only settings the client can actually choose are here. The host's encoder
 * options are stored on the host, keyed by app or by client, and nothing in
 * the launch request lets a client ask for them, so a sweep run from this side
 * cannot vary them.
 */
public final class SweepVariant {

    /** Matches MoonBridge.VIDEO_FORMAT_* so the caller can pass it straight through. */
    public final int videoFormatMask;
    /** Short name for the codec, for the report. */
    public final String codecName;
    public final int bitrateKbps;
    /** Whether the hot threads are pinned to fast cores for this run. */
    public final boolean pinCores;
    /** Frame pacing mode, or -1 to leave the user's setting alone. */
    public final int framePacing;
    public final String framePacingName;

    public SweepVariant(int videoFormatMask, String codecName, int bitrateKbps,
                        boolean pinCores, int framePacing, String framePacingName) {
        this.videoFormatMask = videoFormatMask;
        this.codecName = codecName;
        this.bitrateKbps = bitrateKbps;
        this.pinCores = pinCores;
        this.framePacing = framePacing;
        this.framePacingName = framePacingName;
    }

    /** Groups repeats of the same configuration together. */
    public String key() {
        return codecName + "|" + bitrateKbps + "|" + (pinCores ? "pinned" : "free")
                + "|" + framePacingName;
    }

    /** One line for the report. */
    public String label() {
        StringBuilder out = new StringBuilder(codecName);
        if (bitrateKbps > 0) {
            out.append(' ').append(Math.round(bitrateKbps / 1000f)).append(" Mbps");
        }
        if (framePacingName != null && !framePacingName.isEmpty()) {
            out.append(", ").append(framePacingName);
        }
        out.append(pinCores ? ", pinned" : ", unpinned");
        return out.toString();
    }

    @Override
    public String toString() {
        return "SweepVariant(" + key() + ")";
    }
}
