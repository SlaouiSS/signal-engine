package org.signalengine.interfaces.rest.source;

import jakarta.validation.constraints.NotBlank;
import org.signalengine.application.usecase.SourceConfiguration;

/**
 * The configuration a client provides when registering or editing a source
 * (docs/02-functional-spec.md Section 4.2). Mirrors the application-layer {@link
 * SourceConfiguration} command.
 */
public record SourceConfigurationRequest(
    @NotBlank String type, @NotBlank String name, @NotBlank String reference) {

  SourceConfiguration toCommand() {
    return new SourceConfiguration(type, name, reference);
  }
}
