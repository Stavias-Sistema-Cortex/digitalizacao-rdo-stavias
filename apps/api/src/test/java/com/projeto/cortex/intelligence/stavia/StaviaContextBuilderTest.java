package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.context.StaviaRawContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StaviaContextBuilderTest {

    private final StaviaContextBuilder builder =
            new StaviaContextBuilder();

    @Test
    void shouldFilterEvidenceByWorksite() {
        StaviaRawContext rawContext =
                new StaviaRawContext(
                        "usuario-1",
                        "obra-1",
                        Set.of("RDO_VISUALIZAR"),
                        List.of(
                                evidence(
                                        "RDO",
                                        "rdo-1",
                                        "obra-1",
                                        "RDO_VISUALIZAR"
                                ),
                                evidence(
                                        "RDO",
                                        "rdo-2",
                                        "obra-2",
                                        "RDO_VISUALIZAR"
                                )
                        )
                );

        StaviaContext context =
                builder.build(rawContext);

        assertEquals(1, context.evidences().size());
        assertEquals(
                "rdo-1",
                context.evidences().getFirst().id()
        );
    }

    @Test
    void shouldFilterEvidenceWithoutPermission() {
        StaviaRawContext rawContext =
                new StaviaRawContext(
                        "usuario-1",
                        "obra-1",
                        Set.of(),
                        List.of(
                                evidence(
                                        "CONTRATO",
                                        "contrato-1",
                                        "obra-1",
                                        "CONTRATO_VISUALIZAR"
                                )
                        )
                );

        StaviaContext context =
                builder.build(rawContext);

        assertEquals(0, context.evidences().size());
    }

    @Test
    void shouldKeepPublicEvidenceWithoutSpecificPermission() {
        StaviaRawContext rawContext =
                new StaviaRawContext(
                        "usuario-1",
                        "obra-1",
                        Set.of(),
                        List.of(
                                evidence(
                                        "OBRA",
                                        "obra-1",
                                        "obra-1",
                                        null
                                )
                        )
                );

        StaviaContext context =
                builder.build(rawContext);

        assertEquals(1, context.evidences().size());
    }

    @Test
    void shouldIgnoreMalformedEvidence() {
        StaviaRawContext rawContext =
                new StaviaRawContext(
                        "usuario-1",
                        "obra-1",
                        Set.of(),
                        List.of(
                                new StaviaRawContext.RawEvidence(
                                        "",
                                        "evidence-1",
                                        "obra-1",
                                        null,
                                        "Resumo",
                                        Instant.now(),
                                        true,
                                        Map.of()
                                )
                        )
                );

        StaviaContext context =
                builder.build(rawContext);

        assertEquals(0, context.evidences().size());
    }

    @Test
    void shouldRejectNullContext() {
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.build(null)
        );
    }

    private StaviaRawContext.RawEvidence evidence(
            String type,
            String id,
            String obraId,
            String requiredPermission
    ) {
        return new StaviaRawContext.RawEvidence(
                type,
                id,
                obraId,
                requiredPermission,
                "Resumo da evidência " + id,
                Instant.parse(
                        "2026-06-22T12:00:00Z"
                ),
                true,
                Map.of(
                        "status",
                        "ATIVO"
                )
        );
    }
}
