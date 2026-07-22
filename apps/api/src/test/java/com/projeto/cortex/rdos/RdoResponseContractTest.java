package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RdoResponseContractTest {

    @Test
    void shouldExposeAllRdoHeaderFieldsNeededByOfflineClients() {
        assertThat(recordComponentNames(RdoResponse.class))
                .contains(
                        "condicaoManha",
                        "condicaoTarde",
                        "condicaoNoite",
                        "pluviometriaMm"
                );
    }

    @Test
    void shouldExposeOperationalChildObservationsAndAttachmentMetadata() {
        assertThat(recordComponentNames(RdoResponse.ServicoExecutadoItem.class))
                .contains(
                        "serviceId",
                        "priceVersionId",
                        "revenueCoverageCode",
                        "unitPriceSnapshot",
                        "currency",
                        "revenueAmount",
                        "revenueEvidenceId",
                        "revenueEventId",
                        "acceptedAt",
                        "observacoes"
                );
        assertThat(recordComponentNames(RdoCreateRequest.ServicoExecutadoItem.class))
                .contains("serviceId", "priceVersionId")
                .doesNotContain("custoRealizado");
        assertThat(recordComponentNames(RdoResponse.AlocacaoColaboradorItem.class))
                .contains("observacoes");
        assertThat(recordComponentNames(RdoResponse.AttachmentItem.class))
                .contains("metadata");
    }

    private static String[] recordComponentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}
