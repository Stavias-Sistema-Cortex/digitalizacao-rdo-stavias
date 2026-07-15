package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PapelAcessoServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final PapelAcessoMemoryPublisher memoryPublisher =
            mock(PapelAcessoMemoryPublisher.class);
    private final PapelAcessoService service = new PapelAcessoService(
            jdbcTemplate,
            currentUserService,
            memoryPublisher
    );

    @Test
    void alfaChangesRoleWithExplicitConfirmationAndOptimisticVersion() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 13, 0);
        PapelAcessoUpdateResponse before = new PapelAcessoUpdateResponse(
                "colab-2", "Bruno", "BETA", 4, now.minusDays(1)
        );
        PapelAcessoUpdateResponse after = new PapelAcessoUpdateResponse(
                "colab-2", "Bruno", "ALFA", 5, now
        );
        PapelAcessoUpdateRequest request = new PapelAcessoUpdateRequest(
                "BETA", "ALFA", 4L, "Responsável pela administração regional", true
        );

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(RowMapper.class),
                eq("colab-2")
        )).thenReturn(List.of(before), List.of(after));
        when(jdbcTemplate.update(
                contains("UPDATE colaborador"),
                any(Object[].class)
        )).thenReturn(1);

        PapelAcessoUpdateResponse response = service.alterar("colab-2", request);

        assertThat(response).isEqualTo(after);
        verify(currentUserService).requireAlfa();
        verify(jdbcTemplate).update(
                contains("papel_acesso = ?"),
                eq("ALFA"),
                eq("colab-2"),
                eq("BETA"),
                eq(4L)
        );
        verify(memoryPublisher).papelAlterado(
                before,
                after,
                "alfa-1",
                4L,
                "Responsável pela administração regional"
        );
    }

    @Test
    void rejectsSelfDemotionBeforeWritingAnything() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 13, 0);
        PapelAcessoUpdateResponse current = new PapelAcessoUpdateResponse(
                "alfa-1", "Ana", "ALFA", 7, now
        );
        PapelAcessoUpdateRequest request = new PapelAcessoUpdateRequest(
                "ALFA", "BETA", 7L, "Redução de acesso", true
        );

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(RowMapper.class),
                eq("alfa-1")
        )).thenReturn(List.of(current));

        assertThatThrownBy(() -> service.alterar("alfa-1", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason())
                            .isEqualTo("Um usuário Alfa não pode remover o próprio acesso administrativo.");
                });

        verify(jdbcTemplate, never()).update(contains("UPDATE colaborador"), any(Object[].class));
        verify(memoryPublisher, never()).papelAlterado(any(), any(), any(), anyLong(), any());
    }

    @Test
    void requiresExplicitConfirmationForSensitiveRoleChange() {
        PapelAcessoUpdateRequest request = new PapelAcessoUpdateRequest(
                "BETA", "ALFA", 1L, "Promoção", false
        );

        assertThatThrownBy(() -> service.alterar("colab-2", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason())
                            .isEqualTo("A mudança de acesso exige confirmação explícita.");
                });

        verify(jdbcTemplate, never()).query(
                any(String.class), any(RowMapper.class), any(Object[].class)
        );
    }
}
