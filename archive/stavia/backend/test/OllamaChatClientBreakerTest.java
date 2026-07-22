package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.OllamaUnavailableException;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class OllamaChatClientBreakerTest {

    private final StaviaLlmProperties props = new StaviaLlmProperties();

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-06-25T10:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @Test
    void shouldOpenAfterThresholdAndNotCallServerUntilWindowPasses() {
        MutableClock clock = new MutableClock();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Exatamente 3 falhas esperadas (threshold). A 4ª chamada NÃO deve tocar o servidor.
        for (int i = 0; i < 3; i++) {
            server.expect(anything()).andRespond(withServerError());
        }

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);
        List<OllamaChatClient.ChatMessage> msg =
                List.of(new OllamaChatClient.ChatMessage("user", "oi"));

        for (int i = 0; i < 3; i++) {
            assertThrows(OllamaUnavailableException.class, () -> client.chat(msg, 0.0));
        }
        // breaker aberto: 4ª chamada lança sem tocar o servidor (server.verify() não falha por falta de expectativa)
        assertThrows(OllamaUnavailableException.class, () -> client.chat(msg, 0.0));
        server.verify();

        // Após a janela, ele tenta de novo (precisa de nova expectativa)
        clock.now = clock.now.plus(Duration.ofSeconds(props.getBreakerOpenSeconds() + 1));
        server.reset();
        server.expect(anything()).andRespond(withServerError());
        assertThrows(OllamaUnavailableException.class, () -> client.chat(msg, 0.0));
        server.verify();
    }
}
