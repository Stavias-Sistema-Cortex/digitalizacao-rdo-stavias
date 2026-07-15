package com.projeto.cortex.financeiro.asset;

import static com.projeto.cortex.financeiro.asset.FinancePurchasedAssetDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.AssetRecord;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedItem;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedLinkWrite;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetRepository.PurchasedLinkHistoryWrite;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitResponse;
import com.projeto.cortex.financeiro.unit.FinancialUnitService;
import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FinancePurchasedAssetServiceTest {

    private FinancePurchasedAssetRepository repository;
    private FinancialUnitService units;
    private FinancialAccessService access;
    private FinanceOntologyProjector ontology;
    private FinancePurchasedAssetService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinancePurchasedAssetRepository.class);
        units = mock(FinancialUnitService.class);
        access = mock(FinancialAccessService.class);
        ontology = mock(FinanceOntologyProjector.class);
        service = new FinancePurchasedAssetService(
                repository,
                units,
                access,
                ontology,
                new ObjectMapper().findAndRegisterModules()
        );
        when(repository.findMutation("alfa-1", "asset-mutation-1"))
                .thenReturn(Optional.empty());
        when(repository.lockItem("purchase-1", "item-1"))
                .thenReturn(Optional.of(item("CAPITALIZAVEL", "2.0000", 4L)));
        when(repository.findLinks("item-1")).thenReturn(List.of());
        when(repository.findActiveAsset("asset-1")).thenReturn(Optional.of(
                new AssetRecord("asset-1", "EQ-1", "Escavadeira", "Máquinas")
        ));
        when(repository.findActiveAsset("asset-2")).thenReturn(Optional.of(
                new AssetRecord("asset-2", "EQ-2", "Rolo", "Máquinas")
        ));
        when(units.ensureAssetUnit(eq("asset-1"), eq("alfa-1")))
                .thenReturn(unit("unit-1", "asset-1"));
        when(units.ensureAssetUnit(eq("asset-2"), eq("alfa-1")))
                .thenReturn(unit("unit-2", "asset-2"));
        when(repository.incrementItemVersion("item-1", 4L, "alfa-1"))
                .thenReturn(true);
    }

    @Test
    void rejectsNonCapitalizableItem() {
        when(repository.lockItem("purchase-1", "item-1"))
                .thenReturn(Optional.of(item("CONSUMO", "2.0000", 4L)));

        assertBadRequest(request(existingTargets()), "capitalizável");
    }

    @Test
    void requiresPositiveIntegerQuantity() {
        when(repository.lockItem("purchase-1", "item-1"))
                .thenReturn(Optional.of(item("CAPITALIZAVEL", "1.5000", 4L)));
        assertBadRequest(request(existingTargets()), "inteira");
    }

    @Test
    void requiresOneExplicitTargetPerPurchasedQuantity() {
        assertBadRequest(
                request(List.of(existing("asset-1"))),
                "quantidade"
        );
    }

    @Test
    void requiresExactlyOneExistingOrNewAssetPerTarget() {
        assertBadRequest(
                request(List.of(
                        new PurchasedAssetTarget(
                                "asset-1",
                                new NewAssetRequest("EQ-3", "Usina", "Máquinas")
                        ),
                        existing("asset-2")
                )),
                "exatamente"
        );
    }

    @Test
    void rejectsMissingOrInactiveExistingAsset() {
        when(repository.findActiveAsset("asset-missing"))
                .thenReturn(Optional.empty());
        assertBadRequest(
                request(List.of(existing("asset-1"), existing("asset-missing"))),
                "ativo"
        );
    }

    @Test
    void newAssetRequiresExplicitFieldsAndNeverInfersFromItemText() {
        assertBadRequest(
                request(List.of(
                        created("", null, null),
                        existing("asset-2")
                )),
                "nome"
        );
        verify(repository, never()).insertAsset(any());
    }

    @Test
    void rejectsDuplicateAssetTarget() {
        assertBadRequest(
                request(List.of(existing("asset-1"), existing("asset-1"))),
                "duplicado"
        );
    }

    @Test
    void rejectsStaleItemVersion() {
        ConfirmPurchasedAssetsRequest stale = new ConfirmPurchasedAssetsRequest(
                "asset-mutation-1",
                3L,
                existingTargets(),
                "correlation-1",
                "device-1"
        );
        assertThatThrownBy(() -> service.confirm(
                "purchase-1", "item-1", stale, audit()
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT)
        );
    }

    @Test
    void linksEveryAssetToItsUnitAndHistoriesRealActor() {
        PurchasedAssetResponse response = service.confirm(
                "purchase-1", "item-1", request(existingTargets()), audit()
        );

        assertThat(response.versaoItem()).isEqualTo(5L);
        assertThat(response.ativos())
                .extracting(PurchasedAssetLinkResponse::ativoId)
                .containsExactly("asset-1", "asset-2");
        assertThat(response.ativos())
                .extracting(PurchasedAssetLinkResponse::unidadeControleId)
                .containsExactly("unit-1", "unit-2");
        verify(access).requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_OPERAR
        );

        ArgumentCaptor<PurchasedLinkWrite> links =
                ArgumentCaptor.forClass(PurchasedLinkWrite.class);
        verify(repository, org.mockito.Mockito.times(2)).insertLink(
                links.capture()
        );
        assertThat(links.getAllValues())
                .extracting(PurchasedLinkWrite::actorId)
                .containsOnly("alfa-1");

        ArgumentCaptor<PurchasedLinkHistoryWrite> history =
                ArgumentCaptor.forClass(PurchasedLinkHistoryWrite.class);
        verify(repository, org.mockito.Mockito.times(2)).insertHistory(
                history.capture()
        );
        assertThat(history.getAllValues())
                .extracting(PurchasedLinkHistoryWrite::clientMutationId)
                .containsOnly("asset-mutation-1");
        assertThat(history.getAllValues())
                .extracting(PurchasedLinkHistoryWrite::actorId)
                .containsOnly("alfa-1");
        assertThat(history.getAllValues())
                .extracting(PurchasedLinkHistoryWrite::correlationId)
                .containsOnly("correlation-1");
    }

    private ConfirmPurchasedAssetsRequest request(
            List<PurchasedAssetTarget> targets
    ) {
        return new ConfirmPurchasedAssetsRequest(
                "asset-mutation-1",
                4L,
                targets,
                "correlation-1",
                "device-1"
        );
    }

    private List<PurchasedAssetTarget> existingTargets() {
        return List.of(existing("asset-1"), existing("asset-2"));
    }

    private PurchasedAssetTarget existing(String id) {
        return new PurchasedAssetTarget(id, null);
    }

    private PurchasedAssetTarget created(
            String code,
            String name,
            String category
    ) {
        return new PurchasedAssetTarget(
                null,
                new NewAssetRequest(code, name, category)
        );
    }

    private PurchasedItem item(String nature, String quantity, long version) {
        return new PurchasedItem(
                "purchase-1",
                "item-1",
                "obra-1",
                "Duas escavadeiras amarelas",
                new BigDecimal(quantity),
                nature,
                false,
                version
        );
    }

    private FinancialUnitResponse unit(String id, String assetId) {
        return new FinancialUnitResponse(
                id,
                FinancialUnitType.ATIVO,
                null,
                assetId,
                "ATIVO:" + assetId,
                assetId,
                "ATIVA",
                0L
        );
    }

    private FinanceAuditContext audit() {
        return new FinanceAuditContext(
                "alfa-1", "device-1", "correlation-1", "ONLINE"
        );
    }

    private void assertBadRequest(
            ConfirmPurchasedAssetsRequest request,
            String fragment
    ) {
        assertThatThrownBy(() -> service.confirm(
                "purchase-1", "item-1", request, audit()
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason())
                            .containsIgnoringCase(fragment);
                }
        );
    }
}
