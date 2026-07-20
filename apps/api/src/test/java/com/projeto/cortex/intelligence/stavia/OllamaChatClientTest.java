package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.OllamaUnavailableException;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class OllamaChatClientTest {

    private final StaviaLlmProperties props = new StaviaLlmProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldPostChatAndReturnContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.model").value("gemma4:latest"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}",
                        MediaType.APPLICATION_JSON));

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);
        String content = client.chat(
                List.of(new OllamaChatClient.ChatMessage("user", "oi")), 0.0);

        assertEquals("{\"ok\":true}", content);
        server.verify();
    }

    @Test
    void shouldSendAuthorizationOnlyWhenApiKeyIsConfigured() {
        props.setApiKey("test-only-llm-key");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-only-llm-key"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}",
                        MediaType.APPLICATION_JSON));

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);
        String content = client.chat(
                List.of(new OllamaChatClient.ChatMessage("user", "oi")), 0.0);

        assertEquals("{\"ok\":true}", content);
        server.verify();
    }

    @Test
    void shouldThrowOnServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
                .andRespond(withServerError());

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);

        assertThrows(OllamaUnavailableException.class, () ->
                client.chat(List.of(new OllamaChatClient.ChatMessage("user", "oi")), 0.0));
    }

    @Test
    void shouldThrowOnUnparseableBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
                .andRespond(withSuccess("isso nao e json", MediaType.APPLICATION_JSON));

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);

        assertThrows(OllamaUnavailableException.class, () ->
                client.chat(List.of(new OllamaChatClient.ChatMessage("user", "oi")), 0.0));
    }

    @Test
    void shouldThrowOnEmptyContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"\"}}]}",
                        MediaType.APPLICATION_JSON));

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);

        assertThrows(OllamaUnavailableException.class, () ->
                client.chat(List.of(new OllamaChatClient.ChatMessage("user", "oi")), 0.0));
    }
}
