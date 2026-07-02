package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationPromptBuilder;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaInterpretationPromptBuilderTest {

    private final StaviaInterpretationPromptBuilder builder =
            new StaviaInterpretationPromptBuilder();

    @Test
    void shouldBuildSystemAndUserMessagesListingIntents() {
        List<OllamaChatClient.ChatMessage> messages =
                builder.build(new StaviaQuestion("Quem é o apontador da obra?", "u1", "obra-1"));

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertTrue(messages.get(0).content().contains("CONSULTAR_EQUIPE"));
        assertTrue(messages.get(0).content().contains("ROLE"));
        assertTrue(messages.get(0).content().toLowerCase().contains("apontador"));
        assertTrue(messages.get(0).content().contains("Tem apontador?"));
        assertTrue(messages.get(0).content().contains("Quem é o apontador da obra?"));
        assertTrue(messages.get(0).content().contains("CONSULTAR_RDO"));
        assertTrue(messages.get(1).content().contains("Quem é o apontador da obra?"));
    }

    @Test
    void shouldIncludeRdoOntologyVocabulary() {
        List<OllamaChatClient.ChatMessage> messages =
                builder.build(new StaviaQuestion(
                        "Qual a quantidade prevista de CAP 30/45?",
                        "u1",
                        "obra-1"
                ));

        String system = messages.get(0).content();

        assertTrue(system.contains("material.quantidadePrevista"));
        assertTrue(system.contains("execucaoServico"));
        assertTrue(system.contains("\"operation\""));
        assertTrue(system.contains("\"identity\""));
    }
}
