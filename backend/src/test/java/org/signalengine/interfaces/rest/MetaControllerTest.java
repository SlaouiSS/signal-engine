package org.signalengine.interfaces.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MetaController.class)
@ActiveProfiles("test")
class MetaControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void returnsApiMetadataUnderVersionedBasePath() throws Exception {
    mockMvc
        .perform(get(ApiV1.BASE_PATH + "/meta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Signal Engine"))
        .andExpect(jsonPath("$.apiVersion").value("v1"))
        .andExpect(jsonPath("$.status").value("ok"));
  }
}
