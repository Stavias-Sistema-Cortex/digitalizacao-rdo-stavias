package com.projeto.cortex.financeiro.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.CreateFinancialUnitRequest;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitFilter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FinancialUnitServiceTest {

    @Mock
    private FinancialUnitRepository repository;

    @Mock
    private FinancialAccessService access;

    @Mock
    private FinanceOntologyProjector ontology;

    private FinancialUnitService service;

    @BeforeEach
    void setUp() {
        service = new FinancialUnitService(repository, access, ontology);
    }

    @Test
    void listsOnlyUnitsVisibleToCurrentUser() {
        FinancialUnitRecord worksite = unit(
                "unit-work", FinancialUnitType.OBRA, "obra-1", null,
                "OBRA:1", "Obra 1"
        );
        FinancialUnitRecord asset = unit(
                "unit-asset", FinancialUnitType.ATIVO, null, "asset-1",
                "ATIVO:1", "Escavadeira"
        );
        FinancialUnitFilter filter = new FinancialUnitFilter(null, "ATIVA", null);
        when(repository.list(filter)).thenReturn(List.of(worksite, asset));
        when(access.allowedUnitIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.of(Set.of("unit-work")));

        assertThat(service.list(filter, "beta-1"))
                .extracting(response -> response.id())
                .containsExactly("unit-work");
    }

    @Test
    void alfaGlobalScopeDoesNotRunPerUnitAuthorizationChecks() {
        FinancialUnitFilter filter = new FinancialUnitFilter(
                null, "ATIVA", null
        );
        when(repository.list(filter)).thenReturn(List.of(
                unit(
                        "unit-work", FinancialUnitType.OBRA,
                        "obra-1", null, "OBRA:1", "Obra 1"
                ),
                unit(
                        "unit-admin", FinancialUnitType.ADMINISTRATIVO,
                        null, null, "ADM:1", "Administração"
                )
        ));
        when(access.allowedUnitIds(
                "alfa-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Optional.empty());

        assertThat(service.list(filter, "alfa-1")).hasSize(2);
        verify(access, never()).hasPermissionForUnit(any(), any(), any());
    }

    @Test
    void createsAdministrativeUnitWithRealActorAndOntologyProjection() {
        CreateFinancialUnitRequest request = new CreateFinancialUnitRequest(
                "ADM-SEDE", "Administração central"
        );
        when(repository.findByCode("ADM-SEDE")).thenReturn(Optional.empty());
        when(repository.findById(any())).thenAnswer(invocation -> Optional.of(
                unit(
                        invocation.getArgument(0),
                        FinancialUnitType.ADMINISTRATIVO,
                        null,
                        null,
                        "ADM-SEDE",
                        "Administração central"
                )
        ));

        var response = service.createAdministrative(request, "alfa-1");

        assertThat(response.tipo()).isEqualTo(FinancialUnitType.ADMINISTRATIVO);
        verify(repository).insertAdministrative(
                response.id(), "ADM-SEDE", "Administração central", "alfa-1"
        );
        verify(ontology).success(
                eq("UNIDADE_FINANCEIRA"),
                eq(response.id()),
                eq("Administração central"),
                eq("UNIDADE_FINANCEIRA_CRIADA"),
                eq(null),
                any(),
                eq(Map.of()),
                any(),
                eq(Map.of())
        );
    }

    @Test
    void preservesOfflineAuditUntilOntologyProjection() {
        CreateFinancialUnitRequest request = new CreateFinancialUnitRequest(
                "ADM-CAMPO", "Administração de campo"
        );
        when(repository.findByCode("ADM-CAMPO")).thenReturn(Optional.empty());
        when(repository.findById(any())).thenAnswer(invocation -> Optional.of(
                unit(
                        invocation.getArgument(0),
                        FinancialUnitType.ADMINISTRATIVO,
                        null,
                        null,
                        "ADM-CAMPO",
                        "Administração de campo"
                )
        ));
        FinanceAuditContext audit = new FinanceAuditContext(
                "alfa-1",
                "device-1",
                "correlation-1",
                "OFFLINE"
        );

        service.createAdministrative(request, audit);

        verify(ontology).success(
                eq("UNIDADE_FINANCEIRA"),
                any(),
                eq("Administração de campo"),
                eq("UNIDADE_FINANCEIRA_CRIADA"),
                eq(null),
                eq(audit),
                eq(Map.of()),
                any(),
                eq(Map.of())
        );
    }

    @Test
    void ensuresExistingAssetUnitIdempotentlyWithoutInferringAsset() {
        FinancialUnitRecord existing = unit(
                "unit-asset", FinancialUnitType.ATIVO, null, "asset-1",
                "ATIVO:asset-1", "Escavadeira"
        );
        when(repository.findByAsset("asset-1"))
                .thenReturn(Optional.of(existing));

        var response = service.ensureAssetUnit("asset-1", "alfa-1");

        assertThat(response.id()).isEqualTo("unit-asset");
        verify(repository, never()).findActiveAsset(any());
        verify(repository, never()).insertAsset(any(), any(), any(), any(), any());
        verifyNoInteractions(ontology);
    }

    @Test
    void refusesToCreateUnitForUnknownAsset() {
        when(repository.findByAsset("asset-missing")).thenReturn(Optional.empty());
        when(repository.findActiveAsset("asset-missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureAssetUnit(
                "asset-missing", "alfa-1"
        )).isInstanceOf(ResponseStatusException.class);

        verify(repository, never()).insertAsset(any(), any(), any(), any(), any());
        verifyNoInteractions(ontology);
    }

    private FinancialUnitRecord unit(
            String id,
            FinancialUnitType type,
            String obraId,
            String assetId,
            String code,
            String name
    ) {
        return new FinancialUnitRecord(
                id, type, obraId, assetId, code, name, "ATIVA", 0L
        );
    }
}
