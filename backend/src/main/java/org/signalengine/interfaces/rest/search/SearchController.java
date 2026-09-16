package org.signalengine.interfaces.rest.search;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.signalengine.application.usecase.SemanticSearchUseCase;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Search the knowledge base by meaning (docs/02-functional-spec.md Section 12.3, workflow W9). */
@Tag(name = "Search", description = "Semantic search over the knowledge base")
@RestController
@RequestMapping(SearchController.PATH)
class SearchController {

  static final String PATH = ApiV1.BASE_PATH + "/search";

  private final SemanticSearchUseCase semanticSearch;

  SearchController(SemanticSearchUseCase semanticSearch) {
    this.semanticSearch = semanticSearch;
  }

  @Operation(
      summary = "Search the knowledge base by meaning",
      description =
          "Ranked passages most relevant to the query. An empty `results` list means no match, not "
              + "an error.")
  @ApiResponse(responseCode = "200", description = "Search results (possibly empty)")
  @ApiResponse(responseCode = "400", description = "Missing or invalid query")
  @ApiResponse(responseCode = "503", description = "Search temporarily unavailable")
  @PostMapping
  SearchResponse search(@Valid @RequestBody SearchRequest request) {
    RetrievalResult result = semanticSearch.search(request.query(), request.topKOrDefault());
    return SearchResponse.from(result);
  }
}
