package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.OllamaUnavailableException;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaChatClientTimeoutTest {

    @Test
    void shouldThrowOllamaUnavailableExceptionOnReadTimeout() throws Exception {
        // Inicia servidor local que dorme 1500ms antes de responder
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            StaviaLlmProperties props = new StaviaLlmProperties();
            props.setBaseUrl("http://localhost:" + port + "/v1");

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(300);
            factory.setReadTimeout(300);

            OllamaChatClient client = new OllamaChatClient(
                    RestClient.builder().requestFactory(factory), props, Clock.systemUTC());

            assertThrows(OllamaUnavailableException.class, () ->
                    client.chat(List.of(new OllamaChatClient.ChatMessage("user", "oi")), 0.0),
                    "Esperado OllamaUnavailableException por timeout de leitura");
        } finally {
            server.stop(0);
        }
    }
}
