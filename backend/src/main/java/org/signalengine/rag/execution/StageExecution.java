package org.signalengine.rag.execution;

import java.util.Map;
import org.signalengine.rag.ComponentDescriptor;

/**
 * A note that one pipeline stage ran, and which component provided it.
 *
 * <p>Stages appear in {@link RagExecution#stages()} in execution order. Optional stages that were
 * not configured produce no entry &mdash; the list itself shows which shape the pipeline had.
 *
 * @param component type, implementation id and version of the component that ran
 * @param notes open, immutable per-stage detail (candidate counts, timings added by a decorator,
 *     skipped reasons&hellip;); keys never blank. The core adds nothing here itself.
 */
public record StageExecution(ComponentDescriptor component, Map<String, String> notes) {

  public StageExecution {
    if (component == null) {
      throw new IllegalArgumentException("component must not be null");
    }
    notes = notes == null ? Map.of() : Map.copyOf(notes);
    if (notes.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
      throw new IllegalArgumentException("note keys must not be blank");
    }
  }

  public static StageExecution of(ComponentDescriptor component) {
    return new StageExecution(component, Map.of());
  }
}
