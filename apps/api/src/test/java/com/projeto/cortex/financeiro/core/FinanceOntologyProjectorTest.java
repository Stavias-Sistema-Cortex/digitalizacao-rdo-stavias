package com.projeto.cortex.financeiro.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FinanceOntologyProjectorTest {

    @Test
    void registersUploaderAndConfirmerAsExplicitActorRelations() {
        CortexOperationalMemoryService memory = mock(
                CortexOperationalMemoryService.class
        );
        FinanceOntologyProjector projector = new FinanceOntologyProjector(memory);

        projector.relateActor(
                "uploader-1",
                "DOCUMENTO_FISCAL",
                "documento-1",
                "ENVIOU",
                "Autoria do upload fiscal persistida."
        );
        projector.relateActor(
                "confirmer-1",
                "DOCUMENTO_FISCAL",
                "documento-1",
                "CONFIRMOU",
                "Revisão fiscal confirmada."
        );

        verify(memory).registrarRelacaoAtiva(
                "PESSOA", "uploader-1", "DOCUMENTO_FISCAL", "documento-1",
                "ENVIOU", "CORTEX_FINANCEIRO",
                "Autoria do upload fiscal persistida."
        );
        verify(memory).registrarRelacaoAtiva(
                "PESSOA", "confirmer-1", "DOCUMENTO_FISCAL", "documento-1",
                "CONFIRMOU", "CORTEX_FINANCEIRO",
                "Revisão fiscal confirmada."
        );
    }

    @Test
    void marksOfflineProjectionAsSyncedWithAuthenticatedTrace() {
        CortexOperationalMemoryService memory = mock(
                CortexOperationalMemoryService.class
        );
        FinanceOntologyProjector projector = new FinanceOntologyProjector(memory);
        FinanceAuditContext audit = new FinanceAuditContext(
                "actor-real",
                "device-real",
                "correlation-real",
                "OFFLINE"
        );

        projector.successWithRelations(
                "RATEIO_FINANCEIRO",
                "rateio-1",
                "Rateio 1",
                "RATEIO_FINANCEIRO_CRIADO",
                null,
                audit,
                Map.of(),
                Map.of("id", "rateio-1"),
                List.of(new FinanceOntologyRelation(
                        "UNIDADE_FINANCEIRA",
                        "unit-1",
                        "RATEADO_PARA",
                        "Destino explícito."
                ))
        );

        verify(memory).registrarEventoAuditado(
                anyString(),
                eq("RATEIO_FINANCEIRO"),
                eq("rateio-1"),
                eq("RATEIO_FINANCEIRO_CRIADO"),
                eq("CORTEX_FINANCEIRO"),
                isNull(),
                isNull(),
                eq("actor-real"),
                anyList(),
                eq("OFFLINE"),
                eq("SYNCED"),
                any(),
                any(),
                eq(2),
                anyMap(),
                eq("actor-real"),
                eq("device-real"),
                eq("correlation-real"),
                isNull(),
                anyMap(),
                anyMap(),
                eq("SUCESSO"),
                isNull()
        );
    }

    @Test
    void preservesFailedDomainOutcomeInOperationalEvent() {
        CortexOperationalMemoryService memory = mock(
                CortexOperationalMemoryService.class
        );
        FinanceOntologyProjector projector = new FinanceOntologyProjector(memory);
        FinanceAuditContext audit = FinanceAuditContext.online(
                "actor-real", "correlation-real"
        );

        projector.failureWithRelations(
                "DOCUMENTO_FISCAL", "documento-1", "nota.xml",
                "DOCUMENTO_FISCAL_EXTRACAO_FALHOU", "obra-1", audit,
                Map.of(), Map.of("status", "FALHA"), List.of(),
                "HASH_CLIENTE_DIVERGENTE"
        );

        verify(memory).registrarEventoAuditado(
                anyString(), eq("DOCUMENTO_FISCAL"), eq("documento-1"),
                eq("DOCUMENTO_FISCAL_EXTRACAO_FALHOU"),
                eq("CORTEX_FINANCEIRO"), eq("obra-1"), isNull(),
                eq("actor-real"), anyList(), eq("ONLINE"), eq("ONLINE"),
                any(), any(), eq(2), anyMap(), eq("actor-real"), isNull(),
                eq("correlation-real"), isNull(), anyMap(), anyMap(),
                eq("FALHA"), eq("HASH_CLIENTE_DIVERGENTE")
        );
    }
}
