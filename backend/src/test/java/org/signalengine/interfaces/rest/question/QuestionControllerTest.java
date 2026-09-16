package org.signalengine.interfaces.rest.question;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.signalengine.application.usecase.AskQuestionUseCase;
import org.signalengine.rag.generation.GenerationException;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.provenance.Citation;
import org.signalengine.rag.provenance.Provenance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QuestionController.class)
@ActiveProfiles("test")
class QuestionControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AskQuestionUseCase askQuestion;

  @Test
  void returnsAGroundedAnswerForAValidQuestion() throws Exception {
    Provenance provenance = Provenance.ofSource("feed-1");
    RagAnswer answer =
        RagAnswer.answered(
            "Revenue grew 10%.", List.of(new Citation("p1", provenance, "revenue grew 10%")));
    when(askQuestion.askQuestion("how did revenue change?", 5)).thenReturn(answer);

    mockMvc
        .perform(
            post("/api/v1/questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"question":"how did revenue change?"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answered").value(true))
        .andExpect(jsonPath("$.answer").value("Revenue grew 10%."))
        .andExpect(jsonPath("$.citations[0].passageId").value("p1"))
        .andExpect(jsonPath("$.citations[0].provenance.sourceId").value("feed-1"))
        .andExpect(jsonPath("$.citations[0].quotedText").value("revenue grew 10%"));
  }

  @Test
  void appliesTheRequestedTopK() throws Exception {
    when(askQuestion.askQuestion(eq("a question"), eq(8)))
        .thenReturn(RagAnswer.insufficientEvidence("not enough information"));

    mockMvc
        .perform(
            post("/api/v1/questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"question":"a question","topK":8}"""))
        .andExpect(status().isOk());

    verify(askQuestion).askQuestion("a question", 8);
  }

  @Test
  void reportsAnUnanswerableQuestionWithoutFabricatingAnAnswer() throws Exception {
    when(askQuestion.askQuestion(any(), anyInt()))
        .thenReturn(RagAnswer.insufficientEvidence("not enough information in the knowledge base"));

    mockMvc
        .perform(
            post("/api/v1/questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"question":"what will the stock price be tomorrow?"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answered").value(false))
        .andExpect(jsonPath("$.answer").value("not enough information in the knowledge base"))
        .andExpect(jsonPath("$.citations").isEmpty());
  }

  @Test
  void rejectsABlankQuestionWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"question":""}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    verify(askQuestion, never()).askQuestion(any(), anyInt());
  }

  @Test
  void mapsGenerationFailureToServiceUnavailable() throws Exception {
    when(askQuestion.askQuestion(any(), anyInt()))
        .thenThrow(new GenerationException("the AI provider is unreachable"));

    mockMvc
        .perform(
            post("/api/v1/questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"question":"a question"}"""))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("AI_CAPABILITY_UNAVAILABLE"));
  }
}
