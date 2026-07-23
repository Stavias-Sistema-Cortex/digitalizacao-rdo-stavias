package com.projeto.cortex.financeiro;

import com.projeto.cortex.pdor.PdorApplicationService;
import com.projeto.cortex.pdor.PdorHistoricoResponse;
import com.projeto.cortex.pdor.PdorResultadoResponse;
import com.projeto.cortex.pdor.PdorTriggerType;
import org.springframework.stereotype.Service;

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

    public PdorHistoricoResponse buscarHistorico(
            String obraIdentifier,
            int page,
            int size
    ) {
        return pdor.buscarHistorico(obraIdentifier, page, size);
    }

    public void recalcularAposMudancaRdo(
            String obraId,
            LocalDate referenceDate,
            String originEventId
    ) {
        pdor.calcular(
                obraId,
                referenceDate,
                PdorTriggerType.EVENT,
                originEventId
        );
    }
}
