package com.projeto.cortex.financeiro;

import com.projeto.cortex.pdor.PdorApplicationService;
import com.projeto.cortex.pdor.PdorHistoricoResponse;
import com.projeto.cortex.pdor.PdorResultadoResponse;
import com.projeto.cortex.pdor.PdorTriggerType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;

/**
 * Compatibility route for clients that still call previsao-financeira.
 * Cortex 3 has one operational projection authority: the revenue-only PDOR.
 * Factual purchases, invoices, payments, allocations and ledger entries remain
 * in their dedicated Financeiro services and are not inferred here.
 */
@Service
public class PrevisaoFinanceiraService {

    private final PdorApplicationService pdor;

    public PrevisaoFinanceiraService(PdorApplicationService pdor) {
        this.pdor = pdor;
    }

    public PdorResultadoResponse calcular(
            String obraIdentifier,
            LocalDate referenceDate,
            String triggerType,
            String originEventId
    ) {
        return pdor.calcular(
                obraIdentifier,
                referenceDate,
                PdorTriggerType.from(triggerType),
                originEventId
        );
    }

    public PdorResultadoResponse buscarAtual(String obraIdentifier) {
        return pdor.buscarAtual(obraIdentifier);
    }

    public PdorResultadoResponse buscarAtualSeExistente(
            String obraIdentifier
    ) {
        return pdor.buscarAtualSeExistente(obraIdentifier);
    }

    public PdorHistoricoResponse buscarHistorico(
            String obraIdentifier,
            int page,
            int size
    ) {
        return pdor.buscarHistorico(obraIdentifier, page, size);
    }

    /**
     * Recalcula a projeção atual relendo a data de referência da obra.
     * Cálculos manuais ou históricos usam {@link #calcular} com data explícita.
     */
    public void recalcularAposMudancaRdo(
            String obraId,
            String originEventId
    ) {
        /*
         * Tirar o número antigo é parte da mutação canônica do RDO. Se a
         * mutação voltar atrás, a invalidação também volta; se confirmar, não
         * existe janela em que uma receita de RDO cancelado continue visível.
         */
        pdor.invalidateCurrent(obraId);

        Runnable recalculation = () -> recalcularComSeguranca(
                obraId, originEventId
        );
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            recalculation.run();
                        }
                    }
            );
            return;
        }
        recalculation.run();
    }

    private void recalcularComSeguranca(
            String obraId,
            String originEventId
    ) {
        try {
            pdor.calcular(
                    obraId,
                    null,
                    PdorTriggerType.EVENT,
                    originEventId
            );
        } catch (RuntimeException ignored) {
            /*
             * O RDO aceito é o registro operacional canônico e não pode ser
             * desfeito por uma projeção derivada. Falhas de cálculo PDOR são
             * registradas com correlação depois do rollback da transação de
             * cálculo; detalhes internos não são propagados.
             */
        }
    }
}
