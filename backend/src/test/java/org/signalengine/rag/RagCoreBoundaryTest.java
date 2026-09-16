package org.signalengine.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The RAG core is a reusable library: it depends on no framework, no infrastructure technology and
 * no Signal Engine business type, and its pipeline is wired through contracts, not concrete
 * classes.
 */
class RagCoreBoundaryTest {

  private static final Path RAG_CORE = Path.of("src/main/java/org/signalengine/rag");

  /** Import prefixes a reusable, framework-free RAG core must never pull in. */
  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "org.springframework",
          "jakarta.",
          "javax.",
          "org.hibernate",
          "java.sql",
          "java.net.http",
          "tools.jackson",
          "com.fasterxml",
          "io.github.semanticchunker",
          "org.signalengine.domain",
          "org.signalengine.application",
          "org.signalengine.infrastructure",
          "org.signalengine.interfaces");

  @Test
  void noRagCoreSourceFileImportsAForbiddenPackage() throws Exception {
    List<String> violations = new ArrayList<>();
    for (Path file : ragCoreJavaFiles()) {
      for (String line : Files.readAllLines(file)) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("import ")) {
          continue;
        }
        String imported = trimmed.substring("import ".length()).replace("static ", "").trim();
        for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
          if (imported.startsWith(forbidden)) {
            violations.add(RAG_CORE.relativize(file) + " -> " + imported);
          }
        }
      }
    }
    assertThat(violations).isEmpty();
  }

  @Test
  void theOnlySignalEngineCodeTheRagCoreImportsIsTheRagCoreItself() throws Exception {
    List<String> violations = new ArrayList<>();
    for (Path file : ragCoreJavaFiles()) {
      for (String line : Files.readAllLines(file)) {
        String trimmed = line.strip();
        if (trimmed.startsWith("import org.signalengine.")
            && !trimmed.startsWith("import org.signalengine.rag.")) {
          violations.add(RAG_CORE.relativize(file) + " -> " + trimmed);
        }
      }
    }
    assertThat(violations).isEmpty();
  }

  @Test
  void theStagedPipelinesHoldTheirCollaboratorsOnlyThroughContracts() {
    for (Class<?> pipeline :
        List.of(
            org.signalengine.rag.pipeline.StagedRagPipeline.class,
            org.signalengine.rag.indexing.StagedIndexingPipeline.class)) {
      for (Field field : pipeline.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
          continue;
        }
        Class<?> type = field.getType();
        if (type.equals(Clock.class) || type.getName().startsWith("java.")) {
          continue;
        }
        assertThat(type.isInterface())
            .as(
                "%s field '%s' should be a contract, not the concrete type %s",
                pipeline.getSimpleName(), field.getName(), type.getSimpleName())
            .isTrue();
      }
    }
  }

  @Test
  void everyRagCorePackageDocumentsItsBoundary() throws Exception {
    List<Path> packageInfos = new ArrayList<>();
    try (Stream<Path> files = Files.walk(RAG_CORE)) {
      files
          .filter(path -> path.getFileName().toString().equals("package-info.java"))
          .forEach(packageInfos::add);
    }
    assertThat(packageInfos)
        .as("each RAG core package carries a package-info.java")
        .hasSizeGreaterThanOrEqualTo(9);
  }

  private static List<Path> ragCoreJavaFiles() throws Exception {
    try (Stream<Path> files = Files.walk(RAG_CORE)) {
      return files.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }
}
