package org.signalengine.infrastructure.config;

import java.time.Clock;
import java.util.concurrent.Executor;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.ingestion.CollectFromSourceUseCase;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link StartupIngestionRunner} from the three already-existing ingestion input ports
 * (composition root — CLAUDE.md Section 9; docs/03-technical-spec.md Section 6.2). No new use case,
 * port, or endpoint is introduced here — this only gives the existing ports a caller.
 */
@Configuration(proxyBeanMethods = false)
class StartupIngestionConfiguration {

  /**
   * Runs the startup ingestion pipeline on a single dedicated daemon thread — not a thread pool,
   * since this fires exactly once per process start. Daemon so a hung external call (a slow or
   * unreachable source) can never keep the JVM from shutting down.
   */
  @Bean
  Executor startupIngestionExecutor() {
    return runnable -> {
      Thread thread = new Thread(runnable, "startup-ingestion");
      thread.setDaemon(true);
      thread.start();
    };
  }

  @Bean
  StartupIngestionRunner startupIngestionRunner(
      CollectFromSourceUseCase collectFromSource,
      GroupIntoRelevantInformationUseCase groupIntoRelevantInformation,
      ProcessRelevantInformationUseCase processRelevantInformation,
      Executor startupIngestionExecutor,
      Clock ingestionClock) {
    return new StartupIngestionRunner(
        collectFromSource,
        groupIntoRelevantInformation,
        processRelevantInformation,
        startupIngestionExecutor,
        ingestionClock);
  }
}
