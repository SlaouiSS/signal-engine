package org.signalengine.interfaces.rest.question;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.signalengine.application.usecase.AskQuestionUseCase;
import org.signalengine.interfaces.rest.ApiV1;
import org.signalengine.rag.generation.RagAnswer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask a natural-language question grounded in the knowledge base (docs/02-functional-spec.md
 * Section 13, workflow W10). Not a general-purpose chatbot: every answer is grounded in retrieved
 * content, with citations, or explicitly reports insufficient evidence.
 */
@Tag(name = "Questions", description = "Grounded question answering over the knowledge base")
@RestController
@RequestMapping(QuestionController.PATH)
class QuestionController {

  static final String PATH = ApiV1.BASE_PATH + "/questions";

  private final AskQuestionUseCase askQuestion;

  QuestionController(AskQuestionUseCase askQuestion) {
    this.askQuestion = askQuestion;
  }

  @Operation(
      summary = "Ask a natural-language question grounded in the knowledge base",
      description =
          "Retrieves relevant knowledge and returns a source-grounded answer with citations, or "
              + "`answered = false` when the knowledge base does not support an answer.")
  @ApiResponse(responseCode = "200", description = "Grounded answer (possibly unanswerable)")
  @ApiResponse(responseCode = "400", description = "Missing or invalid question")
  @ApiResponse(responseCode = "503", description = "Question answering temporarily unavailable")
  @PostMapping
  QuestionResponse ask(@Valid @RequestBody QuestionRequest request) {
    RagAnswer answer = askQuestion.askQuestion(request.question(), request.topKOrDefault());
    return QuestionResponse.from(answer);
  }
}
