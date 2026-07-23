package com.projeto.cortex.financeiro;

import com.projeto.cortex.pdor.PdorApplicationService;
import com.projeto.cortex.pdor.PdorTriggerType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PrevisaoFinanceiraPayloadTest {

    @Test
    void legacyRouteDelegatesToTheCanonicalRevenueProjection() {
        PdorApplicationService pdor = mock(PdorApplicationService.class);
        PrevisaoFinanceiraService service =
                new PrevisaoFinanceiraService(pdor);
        LocalDate reference = LocalDate.of(2026, 7, 1);

        service.calcular("obra-1", reference, "API", "event-1");

        verify(pdor).calcular(
                "obra-1",
                reference,
                PdorTriggerType.API,
                "event-1"
        );
    }
}
