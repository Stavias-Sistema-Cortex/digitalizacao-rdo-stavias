package com.projeto.cortex.financeiro.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.financeiro.unit.FinancialUnitRepository;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository.FinancialUnitScope;
import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FinancialGrantServiceTest {

    @Mock
    private FinancialGrantRepository repository;

    @Mock
    private FinancialUnitRepository unitRepository;

    @Mock
    private CortexOperationalMemoryService memory;

    private FinancialGrantService service;

    @BeforeEach
    void setUp() {
        service = new FinancialGrantService(repository, unitRepository, memory);
    }

    @Test
    void activeUnitGrantIsIdempotentAndDoesNotDuplicateAuditEvent() {
        FinancialGrantRecord active = record("ATIVA", FinancialUnitType.ATIVO);
        when(unitRepository.findScopeById("unit-1"))
                .thenReturn(Optional.of(assetScope()));
        when(repository.activeBetaCollaboratorExists("beta-1"))
                .thenReturn(true);
        when(repository.findByUnit(
                "beta-1", "unit-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(active));

        FinancialGrantResponse response = service.grantUnit(
                "unit-1", "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Necessário para acompanhar o equipamento.", "alfa-1"
        );

        assertThat(response.status()).isEqualTo("ATIVA");
        assertThat(response.unidadeControleId()).isEqualTo("unit-1");
        assertThat(response.obraId()).isNull();
        verify(repository, never()).insertUnit(
                anyString(), anyString(), anyString(), any(), any(),
                anyString(), anyString(), any()
        );
        verifyNoInteractions(memory);
    }

    @Test
    void refusesGrantForAdministrativeOrCorporateUnit() {
        when(unitRepository.findScopeById("unit-admin")).thenReturn(
                Optional.of(new FinancialUnitScope(
                        "unit-admin", FinancialUnitType.ADMINISTRATIVO,
                        null, null, "ATIVA"
                ))
        );

        assertThatThrownBy(() -> service.grantUnit(
                "unit-admin", "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Concessão administrativa indevida.", "alfa-1"
        )).isInstanceOf(ResponseStatusException.class);

        verify(repository, never()).activeBetaCollaboratorExists(anyString());
        verifyNoInteractions(memory);
    }

    @Test
    void legacyWorksiteGrantDelegatesToCanonicalWorksiteUnit() {
        when(unitRepository.findScopeByWorksite("obra-1"))
                .thenReturn(Optional.of(worksiteScope()));
        when(repository.activeBetaCollaboratorExists("beta-1"))
                .thenReturn(true);
        when(repository.activeWorksiteLinkExists("beta-1", "obra-1"))
                .thenReturn(true);
        when(repository.findByUnit(
                "beta-1", "unit-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(record("ATIVA", FinancialUnitType.OBRA)));

        FinancialGrantResponse response = service.grant(
                "obra-1", "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Acompanha custos desta obra.", "alfa-1"
        );

        assertThat(response.obraId()).isEqualTo("obra-1");
        assertThat(response.unidadeControleId()).isEqualTo("unit-1");
    }

    @Test
    void refusesWorksiteGrantWithoutActiveOperationalLink() {
        when(unitRepository.findScopeByWorksite("obra-1"))
                .thenReturn(Optional.of(worksiteScope()));
        when(repository.activeBetaCollaboratorExists("beta-1"))
                .thenReturn(true);
        when(repository.activeWorksiteLinkExists("beta-1", "obra-1"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.grant(
                "obra-1", "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Acompanha custos desta obra.", "alfa-1"
        )).isInstanceOf(ResponseStatusException.class);

        verify(repository, never()).insertUnit(
                anyString(), anyString(), anyString(), any(), any(),
                anyString(), anyString(), any()
        );
        verifyNoInteractions(memory);
    }

    @Test
    void reactivationAndRevocationRecordUnitActorAndSchemaTwo() {
        LocalDateTime now = LocalDateTime.parse("2026-07-14T12:00:00");
        FinancialGrantRecord revoked = record(
                "REVOGADA", FinancialUnitType.ATIVO
        );
        FinancialGrantRecord active = record("ATIVA", FinancialUnitType.ATIVO);
        when(unitRepository.findScopeById("unit-1"))
                .thenReturn(Optional.of(assetScope()));
        when(repository.activeBetaCollaboratorExists("beta-1"))
                .thenReturn(true);
        when(repository.databaseNow()).thenReturn(now);
        when(repository.findByUnit(
                "beta-1", "unit-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(
                Optional.of(revoked), Optional.of(active),
                Optional.of(active), Optional.of(revoked)
        );
        when(repository.countActiveForUnit("beta-1", "unit-1"))
                .thenReturn(0L);
        when(repository.reactivate(
                "grant-1", "Nova responsabilidade financeira.",
                "alfa-1", now
        )).thenReturn(true);
        when(repository.revoke(
                "grant-1", "Responsabilidade encerrada.",
                "alfa-1", now
        )).thenReturn(true);

        service.grantUnit(
                "unit-1", "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Nova responsabilidade financeira.", "alfa-1"
        );
        service.revokeUnit(
                "unit-1", "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Responsabilidade encerrada.", "alfa-1"
        );

        verify(memory).registrarRelacaoAtiva(
                "COLABORADOR", "beta-1", "UNIDADE_FINANCEIRA", "unit-1",
                "AUTORIZADO_FINANCEIRO_EM", "GESTAO_PERMISSAO_FINANCEIRA",
                "Concessão financeira explícita por unidade."
        );
        verify(memory).encerrarRelacaoAtiva(
                "COLABORADOR", "beta-1", "UNIDADE_FINANCEIRA", "unit-1",
                "AUTORIZADO_FINANCEIRO_EM"
        );
        verify(memory, org.mockito.Mockito.times(2)).registrarEventoDetalhado(
                any(), eq("PERMISSAO_FINANCEIRA"), eq("grant-1"),
                anyString(), eq("GESTAO_PERMISSAO_FINANCEIRA"), eq(null),
                any(), eq("alfa-1"), any(), eq("ONLINE"), eq("SYNCED"),
                eq(now), eq(now), eq(2), any()
        );
    }

    @Test
    void concurrentRevocationLoserReturnsWinnerWithoutDuplicateAudit() {
        LocalDateTime now = LocalDateTime.parse("2026-07-14T12:00:00");
        FinancialGrantRecord active = record("ATIVA", FinancialUnitType.ATIVO);
        FinancialGrantRecord revoked = record(
                "REVOGADA", FinancialUnitType.ATIVO
        );
        when(unitRepository.findScopeById("unit-1"))
                .thenReturn(Optional.of(assetScope()));
        when(repository.databaseNow()).thenReturn(now);
        when(repository.findByUnit(
                "beta-1", "unit-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(active), Optional.of(revoked));
        when(repository.revoke(
                "grant-1", "Responsabilidade encerrada.", "alfa-1", now
        )).thenReturn(false);

        FinancialGrantResponse response = service.revokeUnit(
                "unit-1", "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Responsabilidade encerrada.", "alfa-1"
        );

        assertThat(response.status()).isEqualTo("REVOGADA");
        verify(repository, never()).countActiveForUnit(anyString(), anyString());
        verifyNoInteractions(memory);
    }

    private FinancialUnitScope assetScope() {
        return new FinancialUnitScope(
                "unit-1", FinancialUnitType.ATIVO,
                null, "asset-1", "ATIVA"
        );
    }

    private FinancialUnitScope worksiteScope() {
        return new FinancialUnitScope(
                "unit-1", FinancialUnitType.OBRA,
                "obra-1", null, "ATIVA"
        );
    }

    private FinancialGrantRecord record(
            String status,
            FinancialUnitType type
    ) {
        return new FinancialGrantRecord(
                "grant-1", "unit-1", type,
                type == FinancialUnitType.OBRA ? "obra-1" : null,
                "beta-1", "Colaborador",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                status, "Justificativa registrada.",
                LocalDateTime.parse("2026-07-14T10:00:00"), "alfa-1",
                "REVOGADA".equals(status)
                        ? LocalDateTime.parse("2026-07-14T11:00:00")
                        : null,
                "REVOGADA".equals(status) ? "alfa-1" : null
        );
    }
}
