package com.limelight.bitratetest;

import java.util.List;
import java.util.Locale;

/**
 * Turns a ladder of measurements into a recommended bitrate plus the reason it stopped
 * where it did.
 *
 * Deliberately free of Android types so the whole decision can be unit tested.
 *
 * The rule is: take the highest rung that stayed clean -- no frame loss, decode time
 * comfortably inside the frame budget, host processing latency flat -- then back off a
 * little, because real games are burstier than a static desktop. The interesting output
 * is not the number, it is which of the three things gave out first, because that says
 * whether the fix is the link, the codec, or the PC.
 */
public final class BitrateTestAnalyzer {

    /** Frame loss at or below this is treated as noise rather than a real ceiling. */
    public static final double CLEAN_LOSS_PERCENT = 0.25;

    /**
     * Share of the frame budget the decoder may spend and still be considered
     * comfortable. 60% of 16.6 ms at 60 fps is 10 ms; 60% of 8.3 ms at 120 fps is 5 ms.
     */
    public static final double DECODE_BUDGET_FRACTION = 0.60;

    /** Host capture+encode latency above this is treated as the host giving out. */
    public static final double HOST_LATENCY_CLEAN_MS = 12.0;

    /** Headroom applied below a ceiling we actually found. */
    public static final double HEADROOM_FRACTION = 0.85;

    /** Applied to the lowest rung when even that rung was already degraded. */
    public static final double FALLBACK_FRACTION = 0.5;

    /** The bitrate seek bar moves in 500 kbps steps, so recommendations land on one. */
    public static final int ROUNDING_KBPS = 500;

    /** Matches the minimum of the bitrate seek bar. */
    public static final int MIN_RECOMMENDED_KBPS = 500;

    /** A step that delivered fewer frames than this did not really stream. */
    public static final int MIN_FRAMES_FOR_VERDICT = 30;

    private BitrateTestAnalyzer() {
    }

    /** The per-frame time budget at this frame rate, in ms. */
    public static double frameBudgetMs(double fps) {
        double effectiveFps = fps > 0 ? fps : 60;
        return 1000.0 / effectiveFps;
    }

    /** The decode time above which the decoder is no longer comfortable, in ms. */
    public static double decodeLimitMs(double fps) {
        return frameBudgetMs(fps) * DECODE_BUDGET_FRACTION;
    }

    /**
     * Whether a step showed no sign of strain: no meaningful frame loss, decode time
     * comfortably inside the frame budget, and the host still keeping up.
     *
     * The ladder uses this to decide when to stop climbing, and {@link #analyze} uses the
     * same rule so the live run and the verdict cannot disagree.
     */
    public static boolean isClean(BitrateStepMeasurement step, double fps) {
        if (step == null || step.isFailed() || !step.hasVideo()
                || step.getFramesReceived() < MIN_FRAMES_FOR_VERDICT) {
            return false;
        }
        if (step.getFrameLossPercent() > CLEAN_LOSS_PERCENT) {
            return false;
        }
        if (step.getAverageDecodeTimeMs() > decodeLimitMs(fps)) {
            return false;
        }
        return !step.hasHostProcessingLatency()
                || step.getAverageHostProcessingLatencyMs() <= HOST_LATENCY_CLEAN_MS;
    }

    public static BitrateRecommendation analyze(List<BitrateStepMeasurement> steps, double fps) {
        if (steps == null || steps.isEmpty()) {
            return new BitrateRecommendation(0, LimitingFactor.NO_DATA, 0, 0,
                    "The test did not collect any usable measurements.");
        }

        double budgetMs = frameBudgetMs(fps);
        double decodeLimit = decodeLimitMs(fps);

        int cleanCeilingKbps = 0;
        BitrateStepMeasurement cleanStep = null;
        BitrateStepMeasurement limitingStep = null;
        LimitingFactor factor = LimitingFactor.NONE;

        for (BitrateStepMeasurement step : steps) {
            if (step.isFailed() || !step.hasVideo() || step.getFramesReceived() < MIN_FRAMES_FOR_VERDICT) {
                limitingStep = step;
                factor = LimitingFactor.STREAM_FAILURE;
                break;
            }

            if (isClean(step, fps)) {
                cleanCeilingKbps = step.getBitrateKbps();
                cleanStep = step;
                continue;
            }

            double loss = step.getFrameLossPercent();
            double decode = step.getAverageDecodeTimeMs();
            boolean hostKnown = step.hasHostProcessingLatency();
            double host = step.getAverageHostProcessingLatencyMs();

            boolean lossBad = loss > CLEAN_LOSS_PERCENT;
            boolean decodeBad = decode > decodeLimit;
            boolean hostBad = hostKnown && host > HOST_LATENCY_CLEAN_MS;

            // Something gave out. Whichever overshot its own threshold by the widest
            // margin is the binding constraint.
            double lossSeverity = lossBad ? loss / CLEAN_LOSS_PERCENT : 0;
            double decodeSeverity = decodeBad ? decode / decodeLimit : 0;
            double hostSeverity = hostBad ? host / HOST_LATENCY_CLEAN_MS : 0;

            if (lossSeverity >= decodeSeverity && lossSeverity >= hostSeverity) {
                factor = LimitingFactor.NETWORK;
            }
            else if (decodeSeverity >= hostSeverity) {
                factor = LimitingFactor.DECODER;
            }
            else {
                factor = LimitingFactor.HOST;
            }
            limitingStep = step;
            break;
        }

        int recommendedKbps;
        if (cleanCeilingKbps > 0) {
            if (factor == LimitingFactor.NONE) {
                // Nothing broke. The top rung is proven clean at exactly that rate, and
                // the real ceiling is somewhere above it, so it already carries headroom.
                recommendedKbps = cleanCeilingKbps;
            }
            else {
                recommendedKbps = roundDown(
                        (int) Math.floor(cleanCeilingKbps * HEADROOM_FRACTION), ROUNDING_KBPS);
            }
        }
        else {
            recommendedKbps = roundDown(
                    (int) Math.floor(steps.get(0).getBitrateKbps() * FALLBACK_FRACTION), ROUNDING_KBPS);
        }
        if (recommendedKbps < MIN_RECOMMENDED_KBPS) {
            recommendedKbps = MIN_RECOMMENDED_KBPS;
        }

        String explanation = explain(factor, cleanStep, cleanCeilingKbps, limitingStep,
                recommendedKbps, budgetMs, decodeLimit, fps);

        return new BitrateRecommendation(recommendedKbps, factor, cleanCeilingKbps,
                limitingStep != null ? limitingStep.getBitrateKbps() : 0, explanation);
    }

    private static String explain(LimitingFactor factor,
                                  BitrateStepMeasurement cleanStep,
                                  int cleanCeilingKbps,
                                  BitrateStepMeasurement limitingStep,
                                  int recommendedKbps,
                                  double budgetMs,
                                  double decodeLimit,
                                  double fps) {
        StringBuilder sb = new StringBuilder();

        if (factor == LimitingFactor.NONE) {
            sb.append("Every rung stayed clean, right up to ").append(mbps(cleanCeilingKbps)).append(". ");
            if (cleanStep != null) {
                sb.append(String.format(Locale.US,
                        "No frames were lost, the decoder took %.1f ms per frame against a %.1f ms budget at %.0f fps",
                        cleanStep.getAverageDecodeTimeMs(), budgetMs, fps > 0 ? fps : 60));
                if (cleanStep.hasHostProcessingLatency()) {
                    sb.append(String.format(Locale.US, ", and the host encoded each frame in %.1f ms",
                            cleanStep.getAverageHostProcessingLatencyMs()));
                }
                sb.append(". ");
            }
            sb.append("Nothing in the chain gave out, so ").append(mbps(recommendedKbps))
                    .append(" is recommended. The real ceiling is somewhere above what this test probed.");
            return sb.toString();
        }

        if (factor == LimitingFactor.NO_DATA) {
            return "The test did not collect any usable measurements.";
        }

        String limitAt = limitingStep != null ? mbps(limitingStep.getBitrateKbps()) : "the first rung";

        switch (factor) {
            case NETWORK:
                sb.append("The network is the constraint. At ").append(limitAt);
                if (limitingStep != null) {
                    sb.append(String.format(Locale.US, ", %.2f%% of frames never arrived",
                            limitingStep.getFrameLossPercent()));
                    sb.append(String.format(Locale.US,
                            ", while the decoder was still comfortable at %.1f ms per frame",
                            limitingStep.getAverageDecodeTimeMs()));
                    if (limitingStep.hasHostProcessingLatency()) {
                        sb.append(String.format(Locale.US, " and the host at %.1f ms",
                                limitingStep.getAverageHostProcessingLatencyMs()));
                    }
                }
                sb.append(". Loss, not compute, is what broke first, so a better link is what moves this number: "
                        + "wired Ethernet, a 5 GHz or 6 GHz band, or a less congested channel. Raising the bitrate "
                        + "further only makes the loss worse.");
                break;

            case DECODER:
                sb.append("This device's decoder is the constraint. At ").append(limitAt);
                if (limitingStep != null) {
                    sb.append(String.format(Locale.US,
                            ", decoding took %.1f ms per frame, past the %.1f ms this device can afford "
                                    + "inside a %.1f ms frame at %.0f fps",
                            limitingStep.getAverageDecodeTimeMs(), decodeLimit, budgetMs, fps > 0 ? fps : 60));
                    sb.append(String.format(Locale.US, ", while only %.2f%% of frames were lost",
                            limitingStep.getFrameLossPercent()));
                }
                sb.append(". The link delivered the stream and the host kept up; the phone or tablet could not "
                        + "decode it in time. A lighter video format (H.264 rather than HEVC or AV1), a lower "
                        + "resolution, or a lower frame rate buys more here than extra bitrate does.");
                break;

            case HOST:
                sb.append("The host's encoder is the constraint. At ").append(limitAt);
                if (limitingStep != null && limitingStep.hasHostProcessingLatency()) {
                    sb.append(String.format(Locale.US,
                            ", the PC needed %.1f ms to capture and encode each frame",
                            limitingStep.getAverageHostProcessingLatencyMs()));
                }
                if (cleanStep != null && cleanStep.hasHostProcessingLatency()) {
                    sb.append(String.format(Locale.US, ", up from %.1f ms at %s",
                            cleanStep.getAverageHostProcessingLatencyMs(), mbps(cleanCeilingKbps)));
                }
                sb.append(". The link delivered the stream and this device decoded it in time, so the fix is on "
                        + "the PC: GPU load, capture settings, or the host's encoder preset.");
                break;

            case STREAM_FAILURE:
            default:
                sb.append("The session did not survive at ").append(limitAt).append('.');
                if (limitingStep != null && limitingStep.getFailureReason() != null) {
                    sb.append(' ').append(limitingStep.getFailureReason());
                }
                else {
                    sb.append(" Too little video arrived at that bitrate to measure anything.");
                }
                break;
        }

        if (cleanCeilingKbps > 0) {
            sb.append(" The highest rung that stayed clean was ").append(mbps(cleanCeilingKbps))
                    .append(", so ").append(mbps(recommendedKbps))
                    .append(" is recommended -- a little under the ceiling, because real games are burstier "
                            + "than the static desktop this test streamed.");
        }
        else {
            sb.append(" Even the lowest rung tested was already degraded, so ").append(mbps(recommendedKbps))
                    .append(" is recommended as a conservative starting point.");
        }

        return sb.toString();
    }

    /** Formats a kbps value the way a person would say it. */
    public static String mbps(int kbps) {
        if (kbps % 1000 == 0) {
            return (kbps / 1000) + " Mbps";
        }
        return String.format(Locale.US, "%.1f Mbps", kbps / 1000.0);
    }

    private static int roundDown(int value, int granularity) {
        if (value <= 0 || granularity <= 0) {
            return 0;
        }
        return (value / granularity) * granularity;
    }
}
