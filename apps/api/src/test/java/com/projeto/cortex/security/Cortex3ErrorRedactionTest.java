package com.projeto.cortex.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.pdor.PdorErrorResponse;
import com.projeto.cortex.pdor.PdorExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Ensures technical failures never echo sensitive diagnostic material to clients. */
class Cortex3ErrorRedactionTest {

    @Test
    void pdorBadRequestNeverEchoesSqlPathsPiiOrSecretMarkers() {
        List<String> sensitiveMarkers = List.of(
                "SELECT * FROM colaborador WHERE cpf = '123.456.789-09'",
                "/srv/cortex/secrets/production.env",
                "ana.silva@example.com",
                "password=super-secret-token",
                "BEGIN PRIVATE KEY"
        );
        PdorExceptionHandler handler = new PdorExceptionHandler();

        for (String marker : sensitiveMarkers) {
            ResponseEntity<PdorErrorResponse> response = handler.handleIllegalArgument(
                    new IllegalArgumentException(marker),
                    new MockHttpServletRequest("POST", "/api/obras/obra-1/pdor/calcular")
            );

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().codigo()).isEqualTo("PDOR_INVALID_REQUEST");
            assertThat(response.getBody().mensagem()).isEqualTo("Parâmetro inválido.");
            assertThat(response.getBody().mensagem()).doesNotContain(marker);
            assertThat(response.getBody().toString()).doesNotContain(marker);
            assertThat(response.getBody().caminho())
                    .isEqualTo("/api/obras/obra-1/pdor/calcular")
                    .doesNotContain("/srv/");
        }
    }
}
