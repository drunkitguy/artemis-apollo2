package com.limelight.bitratetest;

/**
 * The outcome of a ladder run: a bitrate to use, what limited it, and why in words.
 *
 * Pure Java: no Android types, so this is unit testable.
 */
public final class BitrateRecommendation {

    private final int recommendedKbps;
    private final LimitingFactor limitingFactor;
    private final int cleanCeilingKbps;
    private final int limitingStepKbps;
    private final String explanation;

    public BitrateRecommendation(int recommendedKbps,
                                 LimitingFactor limitingFactor,
                                 int cleanCeilingKbps,
                                 int limitingStepKbps,
                                 String explanation) {
        this.recommendedKbps = recommendedKbps;
        this.limitingFactor = limitingFactor;
        this.cleanCeilingKbps = cleanCeilingKbps;
        this.limitingStepKbps = limitingStepKbps;
        this.explanation = explanation;
    }

    /** The bitrate to write into the preference, in kbps. 0 when nothing can be recommended. */
    public int getRecommendedKbps() {
        return recommendedKbps;
    }

    public LimitingFactor getLimitingFactor() {
        return limitingFactor;
    }

    /** Highest rung that stayed clean, in kbps. 0 if none did. */
    public int getCleanCeilingKbps() {
        return cleanCeilingKbps;
    }

    /** The rung that degraded, in kbps. 0 if the ladder never degraded. */
    public int getLimitingStepKbps() {
        return limitingStepKbps;
    }

    /** Plain-language description of what limited the stream. */
    public String getExplanation() {
        return explanation;
    }

    public boolean isApplicable() {
        return recommendedKbps > 0;
    }
}
