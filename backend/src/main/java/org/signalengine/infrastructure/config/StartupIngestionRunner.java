package org.signalengine.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.dedup.GroupingReport;
import org.signalengine.application.ingestion.CollectFromSourceUseCase;
import org.signalengine.application.ingestion.CollectionReport;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.signalengine.application.signal.ProcessingReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Runs the ingestion pipeline once, in the background, right after the application has finished
 * starting — collect &rarr; group &rarr; process, over whatever sources are currently configured
 * and enabled (docs/08-ingestion.md Section 16; docs/11-roadmap.md Phase 4). Without this, a fresh
 * environment stays empty forever: nothing in the running application ever called these use cases.
 *
 * <p><strong>Not a scheduler.</strong> This fires exactly once per process start — the recurring
 * collection cadence (Q9) and whether a manual "collect now" trigger exists (Q8, T17) remain open
 * and are not decided here. Zero configured sources is a harmless no-op.
 *
 * <p><strong>Idempotent by construction, not by anything added here.</strong> Each stage already
 * guarantees, on its own, that re-running it (e.g. after a container restart) does not double
 * collect, double group, or double process — see {@link CollectFromSourceUseCase}, {@link
 * GroupIntoRelevantInformationUseCase}, and {@link ProcessRelevantInformationUseCase}. This class
 * adds no duplicate-prevention of its own.
 *
 * <p><strong>Runs on {@code executor}, never on the {@link ApplicationRunner} thread.</strong> A
 * slow or unreachable source, or an unavailable AI capability, must never delay Spring Boot's own
 * startup or the container's readiness probe, and a failure here must never be treated as a startup
 * failure (an exception thrown from {@link #run(ApplicationArguments)} itself would abort
 * application startup; dispatching the work and catching failures inside it avoids that).
 */
class StartupIngestionRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(StartupIngestionRunner.class);

  private final CollectFromSourceUseCase collectFromSource;
  private final GroupIntoRelevantInformationUseCase groupIntoRelevantInformation;
  private final ProcessRelevantInformationUseCase processRelevantInformation;
  private final Executor executor;
  private final Clock clock;

  StartupIngestionRunner(
      CollectFromSourceUseCase collectFromSource,
      GroupIntoRelevantInformationUseCase groupIntoRelevantInformation,
      ProcessRelevantInformationUseCase processRelevantInformation,
      Executor executor,
      Clock clock) {
    this.collectFromSource = collectFromSource;
    this.groupIntoRelevantInformation = groupIntoRelevantInformation;
    this.processRelevantInformation = processRelevantInformation;
    this.executor = executor;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    executor.execute(this::runPipelineIsolatingFailure);
  }

  /**
   * Collect &rarr; group &rarr; process, strictly in that order — each stage consumes the previous
   * stage's output, so they must not run concurrently. Per-source and per-item failures are already
   * isolated and recorded as {@code ActivityRecord}s inside each use case; only a wholly unexpected
   * failure (a bug, not a business outcome) escapes to this method.
   */
  private void runPipelineIsolatingFailure() {
    Instant startedAt = clock.instant();
    log.info("Startup ingestion run: starting");
    try {
      List<CollectionReport> collected = collectFromSource.collectFromEnabledSources();
      List<GroupingReport> grouped =
          groupIntoRelevantInformation.groupNormalizedRawInformationItems();
      List<ProcessingReport> processed = processRelevantInformation.processPending();
      log.info(
          "Startup ingestion run: completed in {} — {} source(s) collected, {} item(s) grouped, "
              + "{} relevant information record(s) processed",
          Duration.between(startedAt, clock.instant()),
          collected.size(),
          grouped.size(),
          processed.size());
    } catch (RuntimeException failure) {
      log.error(
          "Startup ingestion run: failed after {}",
          Duration.between(startedAt, clock.instant()),
          failure);
    }
  }
}
