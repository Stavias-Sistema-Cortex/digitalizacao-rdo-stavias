package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationService;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationSyncOperationHandler;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetService;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetSyncOperationHandler;
import com.projeto.cortex.financeiro.unit.FinancialUnitService;
import com.projeto.cortex.financeiro.unit.FinancialUnitSyncOperationHandler;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncHandlerCoverageTest {

    @Test
    void registersEveryGeneralizedFinanceOperationExactlyOnce() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FinancialUnitSyncOperationHandler units =
                new FinancialUnitSyncOperationHandler(
                        mock(FinancialUnitService.class),
                        mock(CurrentUserService.class),
                        mapper
                );
        FinanceAllocationSyncOperationHandler allocations =
                new FinanceAllocationSyncOperationHandler(
                        mock(FinanceAllocationService.class),
                        mapper
                );
        FinancePurchasedAssetSyncOperationHandler assets =
                new FinancePurchasedAssetSyncOperationHandler(
                        mock(FinancePurchasedAssetService.class),
                        mapper
                );
        SyncOperationRegistry registry = new SyncOperationRegistry(
                List.of(units, allocations, assets)
        );

        assertThat(registry.require("CREATE_ADMINISTRATIVE"))
                .isSameAs(units);
        assertThat(registry.require("UPSERT")).isSameAs(allocations);
        assertThat(registry.require("CONFIRM")).isSameAs(assets);
        assertThat(registry.operations()).containsExactlyInAnyOrder(
                "CREATE_ADMINISTRATIVE",
                "UPSERT",
                "CONFIRM"
        );
    }
}
