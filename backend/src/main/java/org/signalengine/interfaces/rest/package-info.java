/**
 * REST inbound adapters for the business API (docs/03-technical-spec.md Section 6.6, 12.1).
 *
 * <p>Every controller is a thin adapter: it receives the HTTP request, validates and translates it,
 * calls one or more application <strong>input ports</strong> ({@code
 * org.signalengine.application.usecase}), and translates the result into an HTTP response.
 * Controllers hold no business logic, no persistence, no transactions, and never touch a repository
 * or infrastructure type.
 *
 * <p>Request and response bodies are dedicated {@code *Request} / {@code *Response} records per
 * resource — domain records are never used as the wire contract. All endpoints live under {@code
 * /api/v1}; errors are RFC 9457 {@code application/problem+json} produced by {@link
 * org.signalengine.interfaces.rest.error.ApiExceptionHandler}.
 *
 * <p>{@code MetaController} is the one exception: a technical/bootstrap endpoint with no
 * application use case (CLAUDE.md Section 9).
 */
package org.signalengine.interfaces.rest;
