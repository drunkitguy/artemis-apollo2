package com.limelight.bitratetest;

/**
 * What stopped the ladder. This is the single most useful thing the test produces:
 * it says whether more bitrate, a different codec, or a better link is the answer.
 */
public enum LimitingFactor {
    /** Frames started going missing: the link (or Wi-Fi) could not carry the stream. */
    NETWORK,

    /** The local decoder stopped keeping up with the frame budget. */
    DECODER,

    /** The host took longer and longer to capture and encode each frame. */
    HOST,

    /** The session refused to come up or dropped outright at that bitrate. */
    STREAM_FAILURE,

    /** Nothing broke anywhere on the ladder. */
    NONE,

    /** Not enough usable measurements to say anything. */
    NO_DATA
}
