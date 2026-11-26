package org.signalengine.interfaces.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.signalengine.application.pipeline.RequeueExhaustedWorkUseCase;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

/**
 * The operator command's profile: no web server, no pipeline run, and nothing is requeued unless
 * asked (docs/adr/0019-pipeline-automation.md).
 */
// Replaces the base annotation, so that only the profile turns the pipeline off. Migrations stay on
// here because this context may be the first one to use the shared database.
@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles({"integration", RequeueExhaustedWorkCommand.PROFILE})
class RequeueExhaustedWorkCommandIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private ApplicationContext context;

  @Test
  void theProfileStartsNeitherAWebServerNorThePipeline() {
    assertThat(context).isNotInstanceOf(WebApplicationContext.class);
    assertThat(context.containsBean("pipelineScheduler")).isFalse();
    assertThat(context.getBeanProvider(RequeueExhaustedWorkCommand.class).getIfAvailable())
        .isNotNull();
  }

  @Test
  void withoutTheApplyOptionItOnlyLists() {
    RequeueExhaustedWorkUseCase useCase = mock(RequeueExhaustedWorkUseCase.class);

    new RequeueExhaustedWorkCommand(useCase).run(new DefaultApplicationArguments());

    verify(useCase).findExhaustedWork();
    verify(useCase, never()).requeueRecoverableWork();
  }

  @Test
  void theApplyOptionRequeues() {
    RequeueExhaustedWorkUseCase useCase = mock(RequeueExhaustedWorkUseCase.class);

    new RequeueExhaustedWorkCommand(useCase).run(new DefaultApplicationArguments("--apply"));

    verify(useCase).requeueRecoverableWork();
  }
}
