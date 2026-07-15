package com.projeto.cortex.mensagens.domain;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MessagingOperationalEventServiceTest {

    @Test
    void linksTheAuthenticatedSenderToTheMessage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CortexOperationalMemoryService memory = mock(
                CortexOperationalMemoryService.class
        );
        MessagingOperationalEventService service =
                new MessagingOperationalEventService(jdbc, memory);

        service.recordSuccess(
                "MENSAGEM",
                "mensagem-1",
                "MENSAGEM_ENVIADA",
                new ConversationScope(
                        "conversa-1",
                        ConversationType.OBRA,
                        "obra-1",
                        null,
                        "actor-1",
                        "ATIVA"
                ),
                MessagingAuditContext.online("actor-1", "correlation-1"),
                Map.of(),
                Map.of("corpo", "Mensagem operacional")
        );

        verify(memory).registrarRelacaoAtiva(
                "PESSOA",
                "actor-1",
                "MENSAGEM",
                "mensagem-1",
                "ENVIOU",
                "CORTEX_MENSAGENS",
                "Autoria autenticada da mutação de mensagens."
        );
    }
}
