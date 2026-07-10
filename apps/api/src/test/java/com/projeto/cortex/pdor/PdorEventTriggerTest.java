package com.projeto.cortex.pdor;

import com.projeto.cortex.memory.CortexObservacaoRegistrada;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PdorEventTriggerTest {

    private PdorApplicationService service;
    private ScheduledExecutorService scheduler;
    private PdorEventTrigger trigger;

    @BeforeEach
    void setUp() {
        service = mock(PdorApplicationService.class);
        scheduler = mock(ScheduledExecutorService.class);
        trigger = new PdorEventTrigger(service, 5_000, scheduler);
    }

    @Test
    void novaObservacaoDeRdoAgendaRecalculoDaObra() {
        trigger.aoRegistrarObservacao(observacao(
                "evento-1",
                "RDO",
                "rdo-1",
                "RDO_CRIADO",
                "RDO_API",
                "obra-1"
        ));

        ArgumentCaptor<Runnable> agendado =
                ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(
                agendado.capture(),
                eq(5_000L),
                eq(TimeUnit.MILLISECONDS)
        );

        agendado.getValue().run();

        verify(service).calcular(
                eq("obra-1"),
                isNull(),
                eq(PdorTriggerType.EVENT),
                eq("evento-1")
        );
    }

    @Test
    void observacoesDaMesmaObraSaoCoalescidasEmUmRecalculo() {
        trigger.aoRegistrarObservacao(observacao(
                "evento-1",
                "RDO",
                "rdo-1",
                "RDO_CRIADO",
                "RDO_API",
                "obra-1"
        ));
        trigger.aoRegistrarObservacao(observacao(
                "evento-2",
                "RDO",
                "rdo-2",
                "RDO_CRIADO",
                "RDO_API",
                "obra-1"
        ));

        ArgumentCaptor<Runnable> agendado =
                ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(1)).schedule(
                agendado.capture(),
                anyLong(),
                any(TimeUnit.class)
        );

        agendado.getValue().run();

        verify(service, times(1)).calcular(
                eq("obra-1"),
                isNull(),
                eq(PdorTriggerType.EVENT),
                eq("evento-2")
        );
    }

    @Test
    void observacaoDoProprioPdorNaoDisparaRecalculo() {
        trigger.aoRegistrarObservacao(observacao(
                "evento-1",
                "PDOR",
                "snapshot-1",
                "PDOR_CALCULADO",
                "PDOR",
                "obra-1"
        ));

        verifyNoInteractions(scheduler, service);
    }

    @Test
    void observacaoSemObraVinculadaNaoDisparaRecalculo() {
        trigger.aoRegistrarObservacao(observacao(
                "evento-1",
                "COLABORADOR",
                "colab-1",
                "FREQUENCIA_REGISTRADA",
                "FREQUENCIA",
                null
        ));

        verifyNoInteractions(scheduler, service);
    }

    @Test
    void observacaoSobreAPropriaObraUsaEntidadeComoObra() {
        trigger.aoRegistrarObservacao(observacao(
                "evento-1",
                "OBRA",
                "obra-9",
                "OBRA_ATUALIZADA",
                "OBRA_API",
                null
        ));

        ArgumentCaptor<Runnable> agendado =
                ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(
                agendado.capture(),
                anyLong(),
                any(TimeUnit.class)
        );

        agendado.getValue().run();

        verify(service).calcular(
                eq("obra-9"),
                isNull(),
                eq(PdorTriggerType.EVENT),
                eq("evento-1")
        );
    }

    @Test
    void falhaNoRecalculoNaoPropagaParaOFluxoDeOrigem() {
        doThrow(new IllegalStateException("falha simulada"))
                .when(service)
                .calcular(any(), any(), any(), any());

        trigger.aoRegistrarObservacao(observacao(
                "evento-1",
                "RDO",
                "rdo-1",
                "RDO_CRIADO",
                "RDO_API",
                "obra-1"
        ));

        ArgumentCaptor<Runnable> agendado =
                ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(
                agendado.capture(),
                anyLong(),
                any(TimeUnit.class)
        );

        agendado.getValue().run();
    }

    private CortexObservacaoRegistrada observacao(
            String eventoId,
            String tipoEntidade,
            String entidadeId,
            String tipoEvento,
            String fonte,
            String obraId
    ) {
        return new CortexObservacaoRegistrada(
                eventoId,
                tipoEntidade,
                entidadeId,
                tipoEvento,
                fonte,
                obraId
        );
    }
}
