package org.signalengine.application.usecase;

/**
 * The functional configuration a user provides for a source: a {@code type}, a user-facing {@code
 * name}, and a {@code reference} the collector uses to reach it (docs/02-functional-spec.md Section
 * 4.2). Used both to register a new source and to edit an existing one.
 */
public record SourceConfiguration(String type, String name, String reference) {}
