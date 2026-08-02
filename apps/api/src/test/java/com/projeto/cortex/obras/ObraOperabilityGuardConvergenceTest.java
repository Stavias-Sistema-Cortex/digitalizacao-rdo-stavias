package com.projeto.cortex.obras;

import com.projeto.cortex.common.SyncConvergenceWindow;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObraOperabilityGuardConvergenceTest {

    private final ObraRepository repository = mock(ObraRepository.class);
    private final ObraOperabilityGuard guard =
            new ObraOperabilityGuard(repository);

    @Test
    void archivedWorksiteStillBlocksOutsideTheSyncWindow() {
        when(repository.findWritableIdForShare("obra-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireWritable("obra-1"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND)
                );

        verify(repository, never()).findExistingIdForShare("obra-1");
    }

    @Test
    void syncWindowAcceptsTheArchivedWorksite() {
        // A decisão de produto: arquivar esconde a obra da operação, mas não
        // recusa a convergência do que já foi registrado em campo — o
        // dispositivo que trabalhou offline carrega a única cópia.
        when(repository.findExistingIdForShare("obra-1"))
                .thenReturn(Optional.of("obra-1"));

        try (SyncConvergenceWindow ignored = SyncConvergenceWindow.open()) {
            assertThatCode(() -> guard.requireWritable("obra-1"))
                    .doesNotThrowAnyException();
        }

        verify(repository, never()).findWritableIdForShare("obra-1");
    }

    @Test
    void syncWindowStillRejectsTheMissingWorksiteAndSaysExactlyThat() {
        // A mensagem fundida escondia a causa. No sync, a única recusa
        // possível é inexistência — e o dispositivo precisa ler isso para
        // parar de reenviar em círculo e reidentificar a obra.
        when(repository.findExistingIdForShare("obra-9"))
                .thenReturn(Optional.empty());

        try (SyncConvergenceWindow ignored = SyncConvergenceWindow.open()) {
            assertThatThrownBy(() -> guard.requireWritable("obra-9"))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            exception -> {
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(HttpStatus.NOT_FOUND);
                                assertThat(exception.getReason())
                                        .isEqualTo(
                                                "Obra não encontrada neste servidor."
                                        );
                            }
                    );
        }
    }

    @Test
    void theWindowClosesWithItsScope() {
        try (SyncConvergenceWindow ignored = SyncConvergenceWindow.open()) {
            assertThat(SyncConvergenceWindow.isOpen()).isTrue();
        }
        assertThat(SyncConvergenceWindow.isOpen()).isFalse();

        when(repository.findWritableIdForShare("obra-1"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireWritable("obra-1"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
