package com.projeto.cortex.pdor;

import com.projeto.cortex.memory.CortexObservacaoRegistrada;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Recalcula o PDOR a cada nova observação da ontologia operacional.
 *
 * Cada evento novo do grafo (RDO criado/editado online ou via sync,
 * execução de serviço, programação, dados financeiros) agenda um
 * recálculo da obra afetada. Rajadas (push de sync com várias mutações,
 * seeds) são coalescidas: uma execução por obra por janela de debounce.
 *
 * Observações geradas pelo próprio PDOR são ignoradas para não criar
 * laço de realimentação. O recálculo é best-effort: falhas são logadas
 * e nunca propagam para o fluxo que gravou a observação. A idempotência
 * do PdorApplicationService garante que inputs inalterados não geram
 * snapshot novo.
 */
@Component
public class PdorEventTrigger {

    private static final Logger log =
            LoggerFactory.getLogger(PdorEventTrigger.class);

    private static final String FONTE_PDOR = "PDOR";

    private final PdorApplicationService service;
    private final boolean habilitado;
    private final long debounceMillis;
    private final ScheduledExecutorService scheduler;
    private final Map<String, String> obrasPendentes =
            new ConcurrentHashMap<>();

    @Autowired
    public PdorEventTrigger(
            PdorApplicationService service,
            @Value("${cortex.pdor.gatilho-evento.habilitado:true}")
            boolean habilitado,
            @Value("${cortex.pdor.gatilho-debounce-segundos:5}")
            long debounceSegundos
    ) {
        this(
                service,
                habilitado,
                debounceSegundos * 1_000L,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "pdor-event-trigger");
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    PdorEventTrigger(
            PdorApplicationService service,
            boolean habilitado,
            long debounceMillis,
            ScheduledExecutorService scheduler
    ) {
        this.service = service;
        this.habilitado = habilitado;
        this.debounceMillis = Math.max(0L, debounceMillis);
        this.scheduler = scheduler;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void aoRegistrarObservacao(CortexObservacaoRegistrada observacao) {
        if (!habilitado || observacao == null || !deveDisparar(observacao)) {
            return;
        }

        String obraId = observacao.obraIdResolvido();
        String anterior = obrasPendentes.put(obraId, observacao.eventoId());

        if (anterior == null) {
            scheduler.schedule(
                    () -> recalcular(obraId),
                    debounceMillis,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private boolean deveDisparar(CortexObservacaoRegistrada observacao) {
        if (FONTE_PDOR.equalsIgnoreCase(observacao.fonte())
                || FONTE_PDOR.equalsIgnoreCase(observacao.tipoEntidade())) {
            return false;
        }

        return observacao.vinculadaAObra();
    }

    private void recalcular(String obraId) {
        String eventoOrigemId = obrasPendentes.remove(obraId);

        try {
            PdorResultadoResponse resultado = service.calcular(
                    obraId,
                    null,
                    PdorTriggerType.EVENT,
                    eventoOrigemId
            );
            log.debug(
                    "PDOR recalculado por evento para obra {}: status={}, snapshotExistente={}",
                    obraId,
                    resultado.statusExecucao(),
                    resultado.snapshotExistente()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Falha ao recalcular PDOR por evento para obra {}: {}",
                    obraId,
                    exception.getMessage()
            );
        }
    }

    @PreDestroy
    void encerrar() {
        scheduler.shutdownNow();
    }
}
