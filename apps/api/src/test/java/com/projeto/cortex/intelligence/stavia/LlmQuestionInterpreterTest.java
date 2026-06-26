package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.LlmQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationPromptBuilder;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.springframework.web.client.RestClient;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmQuestionInterpreterTest {

    private LlmQuestionInterpreter interpreterReturning(String cannedJson) {
        OllamaChatClient fake = new OllamaChatClient(
                RestClient.builder(), new StaviaLlmProperties(), Clock.systemUTC()) {
            @Override
            public String chat(List<ChatMessage> messages, double temperature) {
                return cannedJson;
            }
        };
        return new LlmQuestionInterpreter(
                fake, new StaviaInterpretationPromptBuilder(), new StaviaSemanticCatalog());
    }

    private StaviaQuestion q(String text) {
        return new StaviaQuestion(text, "u1", "obra-1");
    }

    @Test
    void paraphrasesProduceSameIntentAndRole() {
        String json = "{\"intent\":\"CONSULTAR_EQUIPE\",\"entities\":[{\"type\":\"ROLE\",\"value\":\"apontador\"}],\"attributes\":[],\"confidence\":0.9}";

        Optional<StaviaInterpretation> a = interpreterReturning(json).interpret(q("Tem apontador?"));
        Optional<StaviaInterpretation> b = interpreterReturning(json).interpret(q("Quem é o apontador da obra?"));

        assertTrue(a.isPresent());
        assertTrue(b.isPresent());
        assertEquals(StaviaIntent.CONSULTAR_EQUIPE, a.get().intent());
        assertEquals(a.get().intent(), b.get().intent());
        assertTrue(a.get().plan().entities().stream()
                .anyMatch(e -> "ROLE".equals(e.type()) && "apontador".equals(e.value())));
        assertTrue(b.get().plan().entities().stream()
                .anyMatch(e -> "ROLE".equals(e.type()) && "apontador".equals(e.value())));
    }

    @Test
    void shouldReturnEmptyOnOutOfRangeConfidence() {
        String json = "{\"intent\":\"CONSULTAR_EQUIPE\",\"entities\":[],\"attributes\":[],\"confidence\":1.5}";
        assertTrue(interpreterReturning(json).interpret(q("qualquer")).isEmpty());
    }

    @Test
    void shouldReturnEmptyOnInvalidIntent() {
        String json = "{\"intent\":\"NAO_EXISTE\",\"entities\":[],\"attributes\":[],\"confidence\":0.9}";
        assertTrue(interpreterReturning(json).interpret(q("qualquer")).isEmpty());
    }

    @Test
    void shouldReturnEmptyOnMalformedJson() {
        assertTrue(interpreterReturning("isso não é json").interpret(q("qualquer")).isEmpty());
    }
}
