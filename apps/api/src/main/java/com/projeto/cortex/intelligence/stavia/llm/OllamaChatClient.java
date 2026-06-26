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

    public OllamaChatClient(RestClient.Builder builder, StaviaLlmProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.restClient = builder.baseUrl(props.getBaseUrl()).build();
    }

    public String chat(List<ChatMessage> messages, double temperature) {
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
            throw new OllamaUnavailableException(
                    "Falha ao chamar o modelo local.", exception);
        }

        if (raw == null || raw.isBlank()) {
            throw new OllamaUnavailableException("Resposta vazia do modelo.");
        }

        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new OllamaUnavailableException("Resposta do modelo sem conteúdo.");
            }
            return content.asText();
        } catch (OllamaUnavailableException e) {
            throw e;
        } catch (Exception exception) {
            throw new OllamaUnavailableException(
                    "Resposta do modelo ilegível.", exception);
        }
    }
}
