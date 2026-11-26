package org.signalengine.interfaces.command;

import java.util.List;
import org.signalengine.application.pipeline.ExhaustedWork;
import org.signalengine.application.pipeline.RequeueExhaustedWorkUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The local operator command for work set aside after its attempts ran out
 * (docs/adr/0019-pipeline-automation.md). It runs only under the {@value #PROFILE} profile, which
 * starts no web server and no pipeline, so there is no HTTP surface: running it takes shell access
 * and the database credentials. Without {@code --apply} it only lists.
 */
@Component
@Profile(RequeueExhaustedWorkCommand.PROFILE)
class RequeueExhaustedWorkCommand implements ApplicationRunner {

  static final String PROFILE = "requeue-exhausted-work";
  private static final String APPLY_OPTION = "apply";

  private static final Logger log = LoggerFactory.getLogger(RequeueExhaustedWorkCommand.class);

  private final RequeueExhaustedWorkUseCase requeueExhaustedWork;

  RequeueExhaustedWorkCommand(RequeueExhaustedWorkUseCase requeueExhaustedWork) {
    this.requeueExhaustedWork = requeueExhaustedWork;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (arguments.containsOption(APPLY_OPTION)) {
      List<ExhaustedWork> requeued = requeueExhaustedWork.requeueRecoverableWork();
      requeued.forEach(work -> log.info("requeued {}", describe(work)));
      log.info(
          "{} piece(s) of work requeued; the next pipeline run processes them", requeued.size());
      return;
    }
    List<ExhaustedWork> exhausted = requeueExhaustedWork.findExhaustedWork();
    exhausted.forEach(work -> log.info("set aside {}", describe(work)));
    log.info(
        "{} piece(s) of work set aside, {} recoverable; nothing changed (add --{} to requeue)",
        exhausted.size(),
        exhausted.stream().filter(ExhaustedWork::recoverable).count(),
        APPLY_OPTION);
  }

  private static String describe(ExhaustedWork work) {
    return "item %s, stage %s, recoverable=%s: %s"
        .formatted(work.rawInformationItemId(), work.stage(), work.recoverable(), work.reason());
  }
}
