package org.signalengine.infrastructure.signal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds for the relevance &rarr; importance &rarr; Signal &rarr; Summary pipeline
 * (docs/adr/0008-relevance-importance-signal-summary.md).
 *
 * <p>{@code batchLimit} caps one sweep of {@link
 * org.signalengine.application.signal.ProcessRelevantInformationUseCase#processPending()}. It is a
 * provisional default open to tuning; it resolves neither the signal-selection criteria (Q4 / T16)
 * nor the summary form/length (Q14).
 */
@ConfigurationProperties("signal-engine.signal-pipeline")
public record SignalPipelineProperties(@DefaultValue("50") int batchLimit) {}
