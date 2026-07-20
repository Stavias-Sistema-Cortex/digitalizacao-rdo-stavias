package com.projeto.cortex.financeiro.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository.FinancialUnitScope;
import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FinancialAccessServiceTest {

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private FinancialGrantRepository repository;

    @Mock
    private FinancialUnitRepository unitRepository;

    private FinancialAccessService service;

    @BeforeEach
    void setUp() {
        service = new FinancialAccessService(
                currentUserService,
                repository,
                unitRepository
        );
    }

    @Test
    void alfaHasEveryFinancialPermissionWithoutGrantRows() {
        when(currentUserService.isAlfa("alfa-1")).thenReturn(true);

        assertThat(service.hasPermission(
                "alfa-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        )).isTrue();

        verifyNoInteractions(repository);
        verifyNoInteractions(unitRepository);
        verify(currentUserService, never()).podeAcessarObra("alfa-1", "obra-1");
    }

    @Test
    void betaNeedsBothWorksiteLinkAndExactActiveGrant() {
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(currentUserService.podeAcessarObra("beta-1", "obra-1"))
                .thenReturn(true);
        when(unitRepository.findScopeByWorksite("obra-1")).thenReturn(
                java.util.Optional.of(scope(
                        "unit-1", FinancialUnitType.OBRA, "obra-1", null
                ))
        );
        when(repository.existsActiveForUnit(
                "beta-1",
                "unit-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(true);

        assertThat(service.hasPermission(
                "beta-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).isTrue();

        when(currentUserService.podeAcessarObra("beta-1", "obra-2"))
                .thenReturn(false);
        assertThat(service.hasPermission(
                "beta-1",
                "obra-2",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).isFalse();
        verify(unitRepository, never()).findScopeByWorksite("obra-2");
    }

    @Test
    void requirePermissionDeniesBeforeDomainAccess() {
        when(currentUserService.requireUserId()).thenReturn("beta-1");
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(currentUserService.podeAcessarObra("beta-1", "obra-1"))
                .thenReturn(true);
        when(unitRepository.findScopeByWorksite("obra-1")).thenReturn(
                java.util.Optional.of(scope(
                        "unit-1", FinancialUnitType.OBRA, "obra-1", null
                ))
        );
        when(repository.existsActiveForUnit(
                "beta-1",
                "unit-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(false);

        assertThatThrownBy(() -> service.requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void allowedObraIdsIntersectsLinksAndActiveGrantsForBeta() {
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(currentUserService.allowedObraIds("beta-1"))
                .thenReturn(java.util.Optional.of(Set.of("obra-1", "obra-2")));
        when(repository.findActiveObraIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Set.of("obra-2", "obra-3"));

        assertThat(service.allowedObraIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).containsExactly("obra-2");
    }

    @Test
    void betaNeedsExplicitGrantForAssetUnitWithoutWorksiteInference() {
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(unitRepository.findScopeById("unit-asset")).thenReturn(
                java.util.Optional.of(scope(
                        "unit-asset", FinancialUnitType.ATIVO,
                        null, "asset-1"
                ))
        );
        when(repository.existsActiveForUnit(
                "beta-1",
                "unit-asset",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(true);

        assertThat(service.hasPermissionForUnit(
                "beta-1",
                "unit-asset",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).isTrue();

        verify(currentUserService, never()).podeAcessarObra(anyString(), anyString());
    }

    @Test
    void betaNeverReceivesAdministrativeOrCorporateScope() {
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(unitRepository.findScopeById("unit-admin")).thenReturn(
                java.util.Optional.of(scope(
                        "unit-admin", FinancialUnitType.ADMINISTRATIVO,
                        null, null
                ))
        );
        when(unitRepository.findScopeById("unit-corp")).thenReturn(
                java.util.Optional.of(scope(
                        "unit-corp", FinancialUnitType.CORPORATIVO,
                        null, null
                ))
        );

        assertThat(service.hasPermissionForUnit(
                "beta-1", "unit-admin",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).isFalse();
        assertThat(service.hasPermissionForUnit(
                "beta-1", "unit-corp",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void resolvesBetaAllowedUnitsInOneRepositoryQuery() {
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(repository.findActiveUnitIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Set.of("unit-work", "unit-asset"));

        assertThat(service.allowedUnitIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).contains(Set.of("unit-work", "unit-asset"));

        verifyNoInteractions(unitRepository);
    }

    private FinancialUnitScope scope(
            String id,
            FinancialUnitType type,
            String obraId,
            String assetId
    ) {
        return new FinancialUnitScope(id, type, obraId, assetId, "ATIVA");
    }
}
