package org.signalengine.interfaces.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metadata endpoint that establishes the {@code /api/v1} convention (base path, JSON, OpenAPI) and
 * gives the frontend and manual checks a versioned endpoint to call.
 *
 * <p>This is a deliberate <strong>technical/bootstrap endpoint</strong> (CLAUDE.md Section 9, "Java
 * backend: dependency inversion in practice"): it exposes no domain concept and orchestrates
 * nothing, so it has no application use case and needs no input port — it returns a static value
 * directly. Business endpoints (sources, interests, signals, search, questions, activity,
 * alert-settings — docs/03-technical-spec.md Section 6.6), added from Phase 3 onward, must instead
 * be driven through an application use-case interface.
 */
@RestController
@RequestMapping(ApiV1.BASE_PATH)
public class MetaController {

  /** Response body for {@code GET /api/v1/meta}. */
  public record MetaResponse(String name, String apiVersion, String status) {}

  @GetMapping("/meta")
  public MetaResponse meta() {
    return new MetaResponse("Signal Engine", "v1", "ok");
  }
}
