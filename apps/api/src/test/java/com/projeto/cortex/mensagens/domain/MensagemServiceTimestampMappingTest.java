package com.projeto.cortex.mensagens.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.storage.StoredObjectRepository;
import com.projeto.cortex.storage.StoredObjectService;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MensagemServiceTimestampMappingTest {

    private static final String MESSAGE_ID =
            "10000000-0000-0000-0000-000000000001";

    @Test
    @SuppressWarnings("unchecked")
    void interpretsTimestampWithoutTimeZoneColumnsAsUtc() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        MensagemService service = new MensagemService(
                jdbc,
                mock(CurrentUserService.class),
                mock(ConversaAccessPolicy.class),
                mock(StoredObjectRepository.class),
                mock(StoredObjectService.class),
                mock(MessagingOperationalEventService.class),
                mock(ObraOperabilityGuard.class),
                mock(PreferenciaDeConversaService.class)
        );

        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn(MESSAGE_ID);
        when(resultSet.getString("conversa_id"))
                .thenReturn("20000000-0000-0000-0000-000000000002");
        when(resultSet.getObject("criado_cliente_em", LocalDateTime.class))
                .thenReturn(LocalDateTime.parse("2026-08-14T12:03:00"));
        when(resultSet.getObject("criado_em", LocalDateTime.class))
                .thenReturn(LocalDateTime.parse("2026-08-14T12:04:00"));
        when(resultSet.getObject("editado_em", LocalDateTime.class))
                .thenReturn(LocalDateTime.parse("2026-08-14T12:05:00"));
        when(resultSet.getObject("deletado_em", LocalDateTime.class))
                .thenReturn(LocalDateTime.parse("2026-08-14T12:06:00"));
        when(jdbc.query(
                contains("WHERE m.id = ?"),
                any(ResultSetExtractor.class),
                eq(MESSAGE_ID)
        )).thenAnswer(invocation -> {
            ResultSetExtractor<MessageResponse> extractor =
                    invocation.getArgument(1);
            return extractor.extractData(resultSet);
        });
        when(jdbc.query(
                contains("FROM mensagem_anexo"),
                any(RowMapper.class),
                eq(MESSAGE_ID)
        )).thenReturn(List.of());

        MessageResponse response = service.get(MESSAGE_ID);

        assertThat(response.criadaNoClienteEm())
                .isEqualTo(Instant.parse("2026-08-14T12:03:00Z"));
        assertThat(response.criadaEm())
                .isEqualTo(Instant.parse("2026-08-14T12:04:00Z"));
        assertThat(response.editadaEm())
                .isEqualTo(Instant.parse("2026-08-14T12:05:00Z"));
        assertThat(response.deletadaEm())
                .isEqualTo(Instant.parse("2026-08-14T12:06:00Z"));
    }
}
