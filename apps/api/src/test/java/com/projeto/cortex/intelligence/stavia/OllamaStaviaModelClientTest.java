package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.OllamaStaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.StaviaModelResponse;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.OllamaUnavailableException;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptEvidence;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.springframework.web.client.RestClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OllamaStaviaModelClientTest {

    private StaviaPrompt prompt() {
        return new StaviaPrompt(
                StaviaVersions.PROMPT, "Instrução.", "Qual foi o último RDO?", "CONSULTAR_RDO",
                List.of(new StaviaPromptEvidence(
                        "RDO:rdo-1", "RDO", "O RDO 1 foi registrado",
                        Instant.parse("2026-06-22T12:00:00Z"), true, Map.of())));
    }

    private OllamaStaviaModelClient clientReturning(String canned, boolean fail) {
        OllamaChatClient chat = new OllamaChatClient(
                RestClient.builder(), new StaviaLlmProperties(), Clock.systemUTC()) {
            @Override
            public String chat(List<ChatMessage> messages, double temperature) {
                if (fail) throw new OllamaUnavailableException("down");
                return canned;
            }
        };
        return new OllamaStaviaModelClient(chat, new DeterministicStaviaModelClient(), new StaviaLlmProperties());
    }

    @Test
    void shouldParseModelJson() {
        String json = "{\"text\":\"O último RDO é o RDO 1.\",\"answerType\":\"FATO\",\"sourceKeys\":[\"RDO:rdo-1\"]}";
        StaviaModelResponse response = clientReturning(json, false).generate(prompt());

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertEquals(List.of("RDO:rdo-1"), response.sourceKeys());
        assertTrue(response.text().contains("RDO 1"));
    }

    @Test
    void shouldFallBackToDeterministicOnFailure() {
        StaviaModelResponse response = clientReturning(null, true).generate(prompt());
        // o fallback determinístico concatena os resumos e cita a fonte
        assertEquals(List.of("RDO:rdo-1"), response.sourceKeys());
        assertTrue(response.text().contains("O RDO 1 foi registrado"));
    }

    @Test
    void shouldCapEvidencesAtMaxEvidences() {
        StaviaLlmProperties props = new StaviaLlmProperties(); // maxEvidences = 50
        String[] capturedUserMessage = {null};

        OllamaChatClient chat = new OllamaChatClient(
                RestClient.builder(), props, Clock.systemUTC()) {
            @Override
            public String chat(List<ChatMessage> messages, double temperature) {
                capturedUserMessage[0] = messages.get(1).content();
                throw new OllamaUnavailableException("capturando");
            }
        };

        List<StaviaPromptEvidence> evidences = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            evidences.add(new StaviaPromptEvidence(
                    "KEY:" + i, "RDO", "Resumo " + i,
                    Instant.parse("2026-06-22T12:00:00Z"), true, Map.of()));
        }
        StaviaPrompt bigPrompt = new StaviaPrompt(
                StaviaVersions.PROMPT, "Instrução.", "Qual o RDO?", "CONSULTAR_RDO", evidences);

        new OllamaStaviaModelClient(chat, new DeterministicStaviaModelClient(), props)
                .generate(bigPrompt);

        assertNotNull(capturedUserMessage[0]);
        long count = capturedUserMessage[0].lines()
                .filter(line -> line.contains("sourceKey="))
                .count();
        assertTrue(count <= props.getMaxEvidences(),
                "Esperado no máximo " + props.getMaxEvidences() + " evidências, mas obteve " + count);
    }
}
