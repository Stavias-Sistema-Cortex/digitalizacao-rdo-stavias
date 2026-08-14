package com.projeto.cortex.mensagens.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

class MessageResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesAllMessageInstantsWithUtcDesignator() throws Exception {
        MessageResponse response = objectMapper.readValue(
                """
                {
                  "id": "message-1",
                  "conversaId": "conversation-1",
                  "autorId": "author-1",
                  "autorNome": "Author",
                  "corpo": "Message",
                  "status": "CRIADA",
                  "clientMutationId": "mutation-1",
                  "criadaNoClienteEm": "2026-08-14T12:03:00Z",
                  "criadaEm": "2026-08-14T12:04:00Z",
                  "editadaEm": "2026-08-14T12:05:00Z",
                  "deletadaEm": "2026-08-14T12:06:00Z",
                  "versao": 1,
                  "anexos": []
                }
                """,
                MessageResponse.class
        );

        JsonNode payload = objectMapper.valueToTree(response);

        assertThat(payload.path("criadaNoClienteEm").asText())
                .isEqualTo("2026-08-14T12:03:00Z");
        assertThat(payload.path("criadaEm").asText())
                .isEqualTo("2026-08-14T12:04:00Z");
        assertThat(payload.path("editadaEm").asText())
                .isEqualTo("2026-08-14T12:05:00Z");
        assertThat(payload.path("deletadaEm").asText())
                .isEqualTo("2026-08-14T12:06:00Z");
    }
}
