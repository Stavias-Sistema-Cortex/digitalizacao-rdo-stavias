package com.projeto.cortex.financeiro;

import com.projeto.cortex.pdor.PdorApplicationService;
import com.projeto.cortex.pdor.PdorTriggerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PrevisaoFinanceiraServiceTransactionTest {

    private final PdorApplicationService pdor =
            mock(PdorApplicationService.class);
    private final PrevisaoFinanceiraService service =
            new PrevisaoFinanceiraService(pdor);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recalculatesImmediatelyWhenThereIsNoMutationTransaction() {
        service.recalcularAposMudancaRdo("obra-1", "evento-1");

        var order = inOrder(pdor);
        order.verify(pdor).invalidateCurrent("obra-1");
        order.verify(pdor).calcular(
                "obra-1",
                null,
                PdorTriggerType.EVENT,
                "evento-1"
        );
    }

    @Test
    void waitsForTheRdoCommitBeforeReadingProjectionInputs() {
        TransactionSynchronizationManager.initSynchronization();

        service.recalcularAposMudancaRdo("obra-1", "evento-1");

        verify(pdor).invalidateCurrent("obra-1");
        verify(pdor, never()).calcular(
                "obra-1",
                null,
                PdorTriggerType.EVENT,
                "evento-1"
        );

        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCommit();

        verify(pdor).calcular(
                "obra-1",
                null,
                PdorTriggerType.EVENT,
                "evento-1"
        );
    }
}
