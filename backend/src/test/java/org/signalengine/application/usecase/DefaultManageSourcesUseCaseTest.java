package org.signalengine.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.Source;

class DefaultManageSourcesUseCaseTest {

  private final SourceRepository sourceRepository = mock(SourceRepository.class);
  private final DefaultManageSourcesUseCase useCase =
      new DefaultManageSourcesUseCase(sourceRepository);

  @Test
  void registersANewSourceAsEnabled() {
    when(sourceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    useCase.registerSource(
        new SourceConfiguration("rss", "Example Feed", "https://example.test/f"));

    ArgumentCaptor<Source> saved = ArgumentCaptor.forClass(Source.class);
    verify(sourceRepository).save(saved.capture());
    assertThat(saved.getValue().id()).isNull();
    assertThat(saved.getValue().type()).isEqualTo("rss");
    assertThat(saved.getValue().name()).isEqualTo("Example Feed");
    assertThat(saved.getValue().reference()).isEqualTo("https://example.test/f");
    assertThat(saved.getValue().enabled()).isTrue();
  }

  @Test
  void rejectsABlankConfigurationFieldWithoutSaving() {
    assertThatThrownBy(
            () ->
                useCase.registerSource(
                    new SourceConfiguration("rss", "  ", "https://example.test")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(sourceRepository, never()).save(any());
  }

  @Test
  void listsAndFindsSources() {
    Source source = existingSource(true);
    when(sourceRepository.findAll()).thenReturn(List.of(source));
    when(sourceRepository.findById(source.id())).thenReturn(Optional.of(source));

    assertThat(useCase.listConfiguredSources()).containsExactly(source);
    assertThat(useCase.findConfiguredSource(source.id())).contains(source);
  }

  @Test
  void updatingAnUnknownSourceReturnsEmpty() {
    UUID unknownId = UUID.randomUUID();
    when(sourceRepository.findById(unknownId)).thenReturn(Optional.empty());

    assertThat(
            useCase.updateSourceConfiguration(
                unknownId, new SourceConfiguration("rss", "n", "https://x.test")))
        .isEmpty();
    verify(sourceRepository, never()).save(any());
  }

  @Test
  void updatesTheConfigurationOfAnExistingSource() {
    Source source = existingSource(true);
    when(sourceRepository.findById(source.id())).thenReturn(Optional.of(source));
    when(sourceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Source updated =
        useCase
            .updateSourceConfiguration(
                source.id(), new SourceConfiguration("atom", "Renamed", "https://example.test/new"))
            .orElseThrow();

    assertThat(updated.id()).isEqualTo(source.id());
    assertThat(updated.type()).isEqualTo("atom");
    assertThat(updated.name()).isEqualTo("Renamed");
    assertThat(updated.reference()).isEqualTo("https://example.test/new");
    assertThat(updated.enabled()).isTrue();
  }

  @Test
  void disablingASourceKeepsItButFlipsTheEnabledFlag() {
    Source source = existingSource(true);
    when(sourceRepository.findById(source.id())).thenReturn(Optional.of(source));
    when(sourceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(useCase.disableSource(source.id()).orElseThrow().enabled()).isFalse();
    assertThat(useCase.enableSource(source.id())).isPresent();
  }

  @Test
  void enablingAnUnknownSourceReturnsEmpty() {
    UUID unknownId = UUID.randomUUID();
    when(sourceRepository.findById(unknownId)).thenReturn(Optional.empty());

    assertThat(useCase.enableSource(unknownId)).isEmpty();
    verify(sourceRepository, never()).save(any());
  }

  private static Source existingSource(boolean enabled) {
    return new Source(
        UUID.randomUUID(),
        "rss",
        "Example",
        "https://example.test/f",
        enabled,
        Instant.now(),
        Instant.now());
  }
}
