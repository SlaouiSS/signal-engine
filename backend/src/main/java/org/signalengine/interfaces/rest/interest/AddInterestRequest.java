package org.signalengine.interfaces.rest.interest;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for adding an interest: the area it belongs to and its natural-language description
 * (docs/02-functional-spec.md Section 5.2).
 */
public record AddInterestRequest(
    @NotBlank String areaOfInterestCode, @NotBlank String description) {}
