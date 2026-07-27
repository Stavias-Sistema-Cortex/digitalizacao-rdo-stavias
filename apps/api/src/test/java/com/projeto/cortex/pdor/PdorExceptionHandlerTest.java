package com.projeto.cortex.pdor;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThat;

class PdorExceptionHandlerTest {

    @Test
    void safeAdviceAlsoCoversTheLegacyForecastController() {
        RestControllerAdvice advice = PdorExceptionHandler.class
                .getAnnotation(RestControllerAdvice.class);

        assertThat(advice).isNotNull();
        assertThat(advice.assignableTypes())
                .contains(PdorController.class, PrevisaoFinanceiraController.class);
    }

    @Test
    void calculationFailureReturnsCorrelationIdWithoutLeakingInternalCause() {
        String internalSecret = "jdbc:postgresql://internal-host/production?password=secret";
        PdorCalculationException exception = new PdorCalculationException(
                "40fd9690-6a6d-44ab-a1ca-1b3519bc3c71",
                new IllegalStateException(internalSecret)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pdor/calcular");

        ResponseEntity<PdorErrorResponse> response = new PdorExceptionHandler()
                .handleCalculationFailure(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().codigo()).isEqualTo("PDOR_CALCULATION_FAILED");
        assertThat(response.getBody().correlationId())
                .isEqualTo("40fd9690-6a6d-44ab-a1ca-1b3519bc3c71");
        assertThat(response.getBody().mensagem())
                .doesNotContain("jdbc", "production", "password", "secret");
        assertThat(response.getBody().caminho()).isEqualTo("/api/pdor/calcular");
        assertThat(response.getBody().toString()).doesNotContain(internalSecret);
    }
}
