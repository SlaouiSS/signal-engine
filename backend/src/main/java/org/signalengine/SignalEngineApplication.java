package org.signalengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point for the Signal Engine backend.
 *
 * <p>The backend owns business state, transactions, orchestration, scheduling, the processing
 * lifecycle, and Java&#8596;Python communication (docs/04-architecture.md Section 3.2). This class
 * is also the outermost point of the composition root described in docs/03-technical-spec.md
 * Section 6.2 — Spring configuration wires port implementations from configuration so the rest of
 * the code stays provider-agnostic.
 *
 * <p>Phase 1 (docs/11-roadmap.md Section 4) only establishes the skeleton: no use cases, no
 * persistence, no AI capabilities, no ingestion.
 */
@SpringBootApplication
public class SignalEngineApplication {

  public static void main(String[] args) {
    SpringApplication.run(SignalEngineApplication.class, args);
  }
}
