package com.projeto.cortex.ontology;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OperationalMemoryFingerprintTest {

    private final OperationalMemoryQueryService service =
            new OperationalMemoryQueryService(
                    mock(JdbcTemplate.class),
                    mock(OperationalMemoryCursorSigner.class)
            );

    @Test
    void lengthPrefixesFieldsSoNewlinesCannotMoveAcrossFilterBoundaries() {
        OperationalMemoryFilter first = filter("x\nY", "Z");
        OperationalMemoryFilter second = filter("x", "Y\nZ");

        assertThat(service.filterHash(first))
                .isNotEqualTo(service.filterHash(second));
    }

    @Test
    void distinguishesAnAbsentFieldFromTheLiteralNullText() {
        assertThat(service.filterHash(filter(null, "Z")))
                .isNotEqualTo(service.filterHash(filter("null", "Z")));
    }

    @Test
    void rejectsCursorReplayAcrossFiltersThatPreviouslyCollided() {
        String firstHash = service.filterHash(filter("x\nY", "Z"));
        String secondHash = service.filterHash(filter("x", "Y\nZ"));
        OperationalMemoryCursorSigner signer = new OperationalMemoryCursorSigner(
                new OperationalMemoryCursorKeyring(
                        "current",
                        "memory-cursor-fingerprint-test-secret"
                                .getBytes(StandardCharsets.UTF_8),
                        null,
                        null
                )
        );
        String token = signer.sign(2L, 1L, "event-1", "scope", firstHash);

        assertThatThrownBy(() -> signer.verify(token, "scope", secondHash))
                .isInstanceOf(OperationalMemoryCursorScopeException.class);
    }

    private OperationalMemoryFilter filter(String query, String entityType) {
        return new OperationalMemoryFilter(
                query,
                entityType,
                "entity",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
