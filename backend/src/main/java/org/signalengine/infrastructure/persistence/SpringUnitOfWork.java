package org.signalengine.infrastructure.persistence;

import java.util.function.Supplier;
import org.signalengine.application.persistence.UnitOfWork;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring-backed {@link UnitOfWork}: runs the work inside a single database transaction managed by
 * Spring's {@link PlatformTransactionManager} (auto-configured for the JDBC {@code DataSource}).
 */
@Component
class SpringUnitOfWork implements UnitOfWork {

  private final TransactionTemplate transactionTemplate;

  SpringUnitOfWork(PlatformTransactionManager transactionManager) {
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Override
  public <R> R inTransaction(Supplier<R> work) {
    return transactionTemplate.execute(status -> work.get());
  }
}
