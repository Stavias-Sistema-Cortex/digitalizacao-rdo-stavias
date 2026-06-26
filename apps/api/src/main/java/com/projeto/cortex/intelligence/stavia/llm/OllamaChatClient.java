package com.projeto.cortex.intelligence.stavia.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OllamaChatClient {

    public record ChatMessage(String role, String content) {}

    private final RestClient restClient;
    private final StaviaLlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    // Circuit-breaker state
    private int consecutiveFailures = 0;
    private java.time.Instant openUntil = java.time.Instant.MIN;

    public OllamaChatClient(RestClient.Builder builder, StaviaLlmProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.restClient = builder.baseUrl(props.getBaseUrl()).build();
    }

    public String chat(List<ChatMessage> messages, double temperature) {
        // Circuit-breaker: short-circuit WITHOUT counting as a new failure
        if (clock.instant().isBefore(openUntil)) {
            throw new OllamaUnavailableException("Modelo local indisponível (circuito aberto).");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("temperature", temperature);
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        body.put("response_format", Map.of("type", "json_object"));

        String raw;
        try {
            raw = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException exception) {
            throw fail("Falha ao chamar o modelo local.", exception);
        }

        if (raw == null || raw.isBlank()) {
            throw fail("Resposta vazia do modelo.", null);
        }

        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw fail("Resposta do modelo sem conteúdo.", null);
            }
            consecutiveFailures = 0;
            return content.asText();
        } catch (OllamaUnavailableException e) {
            throw e;
        } catch (Exception exception) {
            throw fail("Resposta do modelo ilegível.", exception);
        }
    }

    private RuntimeException fail(String message, Throwable cause) {
        registerFailure();
        return cause == null
                ? new OllamaUnavailableException(message)
                : new OllamaUnavailableException(message, cause);
    }

    private void registerFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= props.getBreakerFailureThreshold()) {
            openUntil = clock.instant().plusSeconds(props.getBreakerOpenSeconds());
            consecutiveFailures = 0;
        }
    }
}
