package org.signalengine.domain;

/**
 * One of the six fixed areas of interest (docs/05-data-model.md Section 5).
 *
 * <p>Reference data: identity ({@code code}) and a user-facing {@code name}, nothing more. The set
 * is fixed and seeded by migration V2; this type is read, not created, by the application.
 */
public record AreaOfInterest(String code, String name) {}
