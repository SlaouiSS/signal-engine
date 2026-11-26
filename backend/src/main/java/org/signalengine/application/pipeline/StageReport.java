package org.signalengine.application.pipeline;

import java.time.Duration;
import java.util.Map;

/**
 * What one stage of a pipeline cycle did.
 *
 * @param stage the name of the stage
 * @param duration how long it ran
 * @param outcomes how many units of work ended with each outcome
 * @param failed whether the stage itself failed unexpectedly, as opposed to some of its units
 */
public record StageReport(
    String stage, Duration duration, Map<String, Integer> outcomes, boolean failed) {}
