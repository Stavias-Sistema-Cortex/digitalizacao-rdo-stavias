package com.projeto.cortex.financeiro.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FinancialGrantServiceTest {

    private FinancialGrantRepository repository;
    private CortexOperationalMemoryService memory;
    private FinancialGrantService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinancialGrantRepository.class);
        memory = mock(CortexOperationalMemoryService.class);
        service = new FinancialGrantService(repository, memory);
    }

    @Test
    void activeGrantIsIdempotentAndDoesNotDuplicateAuditEvent() {
        FinancialGrantRecord active = record("ATIVA");
        when(repository.worksiteExists("obra-1")).thenReturn(true);
        when(repository.activeBetaCollaboratorExists("beta-1")).thenReturn(true);
        when(repository.find(
                "beta-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(active));

        FinancialGrantResponse response = service.grant(
                "obra-1",
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Necessário para acompanhar custos.",
                "alfa-1"
        );

        assertThat(response.status()).isEqualTo("ATIVA");
        verify(repository, never()).insert(
                anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), any()
        );
        verifyNoInteractions(memory);
    }

    @Test
    void refusesRedundantGrantToAlfaThatCouldSurviveFutureDowngrade() {
        when(repository.worksiteExists("obra-1")).thenReturn(true);
        when(repository.activeBetaCollaboratorExists("alfa-target"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.grant(
                "obra-1",
                "alfa-target",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Concessão redundante não permitida.",
                "alfa-actor"
        )).isInstanceOf(ResponseStatusException.class);

        verify(repository, never()).insert(
                anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), any()
        );
        verifyNoInteractions(memory);
    }

    @Test
    void reactivationAndRevocationProduceScopedOntologyEvents() {
        LocalDateTime now = LocalDateTime.parse("2026-07-14T12:00:00");
        FinancialGrantRecord revoked = record("REVOGADA");
        FinancialGrantRecord active = record("ATIVA");
        when(repository.worksiteExists("obra-1")).thenReturn(true);
        when(repository.activeBetaCollaboratorExists("beta-1")).thenReturn(true);
        when(repository.databaseNow()).thenReturn(now);
        when(repository.find(
                "beta-1", "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(
                Optional.of(revoked),
                Optional.of(active),
                Optional.of(active),
                Optional.of(revoked)
        );
        when(repository.countActive("beta-1", "obra-1")).thenReturn(0L);
        when(repository.reactivate(
                "grant-1",
                "Nova responsabilidade financeira.",
                "alfa-1",
                now
        )).thenReturn(true);
        when(repository.revoke(
                "grant-1",
                "Responsabilidade encerrada.",
                "alfa-1",
                now
        )).thenReturn(true);

        service.grant(
                "obra-1",
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Nova responsabilidade financeira.",
                "alfa-1"
        );
        service.revoke(
                "obra-1",
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Responsabilidade encerrada.",
                "alfa-1"
        );

        verify(repository).reactivate(
                "grant-1",
                "Nova responsabilidade financeira.",
                "alfa-1",
                now
        );
        verify(repository).revoke(
                "grant-1",
                "Responsabilidade encerrada.",
                "alfa-1",
                now
        );
        verify(memory).registrarRelacaoAtiva(
                "COLABORADOR",
                "beta-1",
                "OBRA",
                "obra-1",
                "AUTORIZADO_FINANCEIRO_EM",
                "GESTAO_PERMISSAO_FINANCEIRA",
                "Concessão financeira explícita por obra."
        );
        verify(memory).encerrarRelacaoAtiva(
                "COLABORADOR",
                "beta-1",
                "OBRA",
                "obra-1",
                "AUTORIZADO_FINANCEIRO_EM"
        );
        verify(memory, org.mockito.Mockito.times(2))
                .registrarEventoDetalhado(
                        any(),
                        eq("PERMISSAO_FINANCEIRA"),
                        eq("grant-1"),
                        anyString(),
                        eq("GESTAO_PERMISSAO_FINANCEIRA"),
                        eq("obra-1"),
                        any(),
                        eq("alfa-1"),
                        any(),
                        eq("ONLINE"),
                        eq("SYNCED"),
                        eq(now),
                        eq(now),
                        eq(1),
                        any()
                );
    }

    @Test
    void concurrentReactivationLoserReturnsWinnerWithoutDuplicateAudit() {
        LocalDateTime now = LocalDateTime.parse("2026-07-14T12:00:00");
        FinancialGrantRecord revoked = record("REVOGADA");
        FinancialGrantRecord active = record("ATIVA");
        when(repository.worksiteExists("obra-1")).thenReturn(true);
        when(repository.activeBetaCollaboratorExists("beta-1")).thenReturn(true);
        when(repository.databaseNow()).thenReturn(now);
        when(repository.find(
                "beta-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(revoked), Optional.of(active));
        when(repository.reactivate(
                "grant-1",
                "Nova responsabilidade financeira.",
                "alfa-1",
                now
        )).thenReturn(false);

        FinancialGrantResponse response = service.grant(
                "obra-1",
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Nova responsabilidade financeira.",
                "alfa-1"
        );

        assertThat(response.status()).isEqualTo("ATIVA");
        verifyNoInteractions(memory);
    }

    @Test
    void concurrentRevocationLoserReturnsWinnerWithoutDuplicateAudit() {
        LocalDateTime now = LocalDateTime.parse("2026-07-14T12:00:00");
        FinancialGrantRecord active = record("ATIVA");
        FinancialGrantRecord revoked = record("REVOGADA");
        when(repository.databaseNow()).thenReturn(now);
        when(repository.find(
                "beta-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(active), Optional.of(revoked));
        when(repository.revoke(
                "grant-1",
                "Responsabilidade encerrada.",
                "alfa-1",
                now
        )).thenReturn(false);

        FinancialGrantResponse response = service.revoke(
                "obra-1",
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Responsabilidade encerrada.",
                "alfa-1"
        );

        assertThat(response.status()).isEqualTo("REVOGADA");
        verify(repository, never()).countActive(anyString(), anyString());
        verifyNoInteractions(memory);
    }

    private FinancialGrantRecord record(String status) {
        return new FinancialGrantRecord(
                "grant-1",
                "obra-1",
                "beta-1",
                "Colaborador",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                status,
                "Justificativa registrada.",
                LocalDateTime.parse("2026-07-14T10:00:00"),
                "alfa-1",
                "REVOGADA".equals(status)
                        ? LocalDateTime.parse("2026-07-14T11:00:00")
                        : null,
                "REVOGADA".equals(status) ? "alfa-1" : null
        );
    }
}
