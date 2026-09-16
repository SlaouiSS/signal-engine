package org.signalengine.interfaces.rest.interest;

import jakarta.validation.constraints.NotBlank;

/** Body for editing an interest's natural-language description. */
public record UpdateInterestDescriptionRequest(@NotBlank String description) {}
