package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.catalog.ServicePriceVersion;
import java.math.BigDecimal;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RdoMonetaryDataMinimizationContractTest {

    @Test
    void commonRdoContextMustExposeOnlyOpaquePriceReferences() {
        assertThat(recordComponentNames(RdoContextResponse.ServicePriceChoice.class))
                .contains(
                        "id", "serviceId", "unit", "version",
                        "validFrom", "effectiveValidTo"
                )
                .doesNotContain("unitPrice", "currency");
    }

    @Test
    void commonRdoResponseMustNotExposeMonetaryEvidenceValues() {
        assertThat(recordComponentNames(RdoResponse.ServicoExecutadoItem.class))
                .contains(
                        "serviceId", "priceVersionId", "revenueCoverageCode",
                        "revenueEvidenceId", "revenueEventId", "acceptedAt"
                )
                .doesNotContain(
                        "unitPriceSnapshot", "currency", "revenueAmount"
                );
    }

    @Test
    void serializedOperationalContractsNeverContainMoneyForAnyRole()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RdoContextResponse.ServicePriceChoice opaquePrice =
                new RdoContextResponse.ServicePriceChoice(
                        "price", "service", "M2", 3,
                        LocalDate.of(2026, 7, 22), null
                );
        RdoResponse.ServicoExecutadoItem operational =
                new RdoResponse.ServicoExecutadoItem(
                        "execution", "service", "price", "Service", null,
                        BigDecimal.ONE, "M2", null, null, null, "DIURNO",
                        "VALIDADA", "RECEITA_MEDIDA", "ACCEPTED_EXACT",
                        "evidence", "event", null, false, false, null
                );

        for (JsonNode json : new JsonNode[]{
                mapper.valueToTree(opaquePrice),
                mapper.valueToTree(operational)
        }) {
            assertThat(json.has("unitPrice")).isFalse();
            assertThat(json.has("unitPriceSnapshot")).isFalse();
            assertThat(json.has("currency")).isFalse();
            assertThat(json.has("revenueAmount")).isFalse();
        }
    }

    @Test
    void financeSpecificPriceContractRetainsAuthorizedMoneyFields() {
        assertThat(recordComponentNames(ServicePriceVersion.class))
                .contains("unitPrice", "currency");
    }

    private static String[] recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}
