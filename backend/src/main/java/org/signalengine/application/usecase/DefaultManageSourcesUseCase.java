package org.signalengine.application.usecase;

import static org.signalengine.application.usecase.Guards.requireText;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.Source;

/** Default implementation of {@link ManageSourcesUseCase}. */
public final class DefaultManageSourcesUseCase implements ManageSourcesUseCase {

  private final SourceRepository sourceRepository;

  public DefaultManageSourcesUseCase(SourceRepository sourceRepository) {
    this.sourceRepository = sourceRepository;
  }

  @Override
  public Source registerSource(SourceConfiguration configuration) {
    validate(configuration);
    Source newSource =
        new Source(
            null,
            configuration.type(),
            configuration.name(),
            configuration.reference(),
            true,
            null,
            null);
    return sourceRepository.save(newSource);
  }

  @Override
  public List<Source> listConfiguredSources() {
    return sourceRepository.findAll();
  }

  @Override
  public Optional<Source> findConfiguredSource(UUID sourceId) {
    return sourceRepository.findById(sourceId);
  }

  @Override
  public Optional<Source> updateSourceConfiguration(
      UUID sourceId, SourceConfiguration configuration) {
    validate(configuration);
    return sourceRepository
        .findById(sourceId)
        .map(
            source ->
                sourceRepository.save(
                    source.withConfiguration(
                        configuration.type(), configuration.name(), configuration.reference())));
  }

  @Override
  public Optional<Source> disableSource(UUID sourceId) {
    return changeEnablement(sourceId, false);
  }

  @Override
  public Optional<Source> enableSource(UUID sourceId) {
    return changeEnablement(sourceId, true);
  }

  private Optional<Source> changeEnablement(UUID sourceId, boolean enabled) {
    return sourceRepository
        .findById(sourceId)
        .map(source -> sourceRepository.save(source.withEnabled(enabled)));
  }

  private static void validate(SourceConfiguration configuration) {
    requireText(configuration.type(), "source type");
    requireText(configuration.name(), "source name");
    requireText(configuration.reference(), "source reference");
  }
}
