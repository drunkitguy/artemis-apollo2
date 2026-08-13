package com.limelight.bitratetest;

import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Audio sink for the connection test: accepts the audio stream and throws it away.
 *
 * The test is about video, and the user is looking at a progress card rather than
 * playing anything, so opening a real AudioTrack would only add noise -- literally and
 * to the measurement. The audio stream itself is still negotiated and still crosses the
 * network, so the bandwidth being measured is the bandwidth a real session would use.
 */
public class SilentAudioRenderer implements AudioRenderer {

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        // 0 means "renderer initialized"
        return 0;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
    }

    @Override
    public void cleanup() {
    }
}
