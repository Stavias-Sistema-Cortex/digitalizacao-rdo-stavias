package com.projeto.cortex.intelligence.stavia.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Primary
@ConditionalOnProperty(prefix = "cortex.stavia", name = "generator-mode", havingValue = "prompt")
public class OllamaStaviaModelClient implements StaviaModelClient {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OllamaStaviaModelClient.class);

    private final OllamaChatClient chatClient;
    private final DeterministicStaviaModelClient fallback;
    private final StaviaLlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaStaviaModelClient(
            OllamaChatClient chatClient,
            DeterministicStaviaModelClient fallback,
            StaviaLlmProperties props
    ) {
        this.chatClient = chatClient;
        this.fallback = fallback;
        this.props = props;
    }

    @Override
    public StaviaModelResponse generate(StaviaPrompt prompt) {
        try {
            List<OllamaChatClient.ChatMessage> messages = List.of(
                    new OllamaChatClient.ChatMessage("system", systemMessage(prompt)),
                    new OllamaChatClient.ChatMessage("user", userMessage(prompt)));

            String content = chatClient.chat(messages, 0.3);
            JsonNode root = mapper.readTree(content);

            String text = root.path("text").asText("");
            if (text.isBlank()) {
                return fallback.generate(prompt);
            }
            StaviaAnswerType answerType = parseAnswerType(root.path("answerType").asText(""));
            List<String> sourceKeys = new ArrayList<>();
            for (JsonNode key : root.path("sourceKeys")) {
                sourceKeys.add(key.asText());
            }
            return new StaviaModelResponse(text, answerType, sourceKeys);
        } catch (Exception exception) {
            LOGGER.warn("Geração LLM falhou ({}); usando gerador determinístico.",
                    exception.getMessage());
            return fallback.generate(prompt);
        }
    }

    private StaviaAnswerType parseAnswerType(String value) {
        try {
            return StaviaAnswerType.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return StaviaAnswerType.FATO;
        }
    }

    private String systemMessage(StaviaPrompt prompt) {
        return prompt.systemInstruction()
                + "\nResponda em JSON: {\"text\":\"...\",\"answerType\":\"FATO|INFERENCIA|RECOMENDACAO|INFORMACAO_INSUFICIENTE\",\"sourceKeys\":[\"...\"]}."
                + " Cite em sourceKeys apenas as chaves fornecidas nas evidências.";
    }

    private String userMessage(StaviaPrompt prompt) {
        StringBuilder builder = new StringBuilder();
        builder.append("Pergunta: ").append(prompt.userQuestion()).append("\n\nEvidências:\n");
        prompt.evidences().stream()
                .limit(props.getMaxEvidences())
                .forEach(evidence -> builder.append("- sourceKey=").append(evidence.sourceKey())
                        .append(" | ").append(evidence.summary()).append("\n"));
        return builder.toString();
    }
}
