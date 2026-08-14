package com.projeto.cortex.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.sync.SyncPushRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtcLocalDateTimesTest {

    private static final LocalDateTime EXPECTED_UTC =
            LocalDateTime.parse("2026-08-14T12:03:00");

    @Test
    void normalizesOffsetAndZWhileKeepingLegacyUtcWallClock() {
        assertThat(UtcLocalDateTimes.parse("2026-08-14T12:03:00Z"))
                .isEqualTo(EXPECTED_UTC);
        assertThat(UtcLocalDateTimes.parse("2026-08-14T09:03:00-03:00"))
                .isEqualTo(EXPECTED_UTC);
        assertThat(UtcLocalDateTimes.parse("2026-08-14T12:03:00"))
                .isEqualTo(EXPECTED_UTC);
    }

    @Test
    void appliesTheSameNormalizationAtTheCanonicalSyncBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        SyncPushRequest request = mapper.readValue(
                """
                {
                  "dispositivoId": "device-1",
                  "mutacoes": [{
                    "clientMutationId": "mutation-1",
                    "criadaNoClienteEm": "2026-08-14T09:03:00-03:00"
                  }]
                }
                """,
                SyncPushRequest.class
        );

        assertThat(request.mutacoes())
                .singleElement()
                .extracting(SyncPushRequest.MutacaoCliente::criadaNoClienteEm)
                .isEqualTo(EXPECTED_UTC);
    }
}
