package com.projeto.cortex.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CortexOperationalMemoryServiceTest {

    @Test
    void persistsAuthoritativeEntityVersionOnOperationalEvent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CortexOperationalMemoryService service = new CortexOperationalMemoryService(
                jdbcTemplate,
                new ObjectMapper(),
                publisher
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 13, 30);

        when(jdbcTemplate.queryForObject(
                contains("SELECT commit_seq"),
                eq(Long.class),
                eq("evento-1")
        )).thenThrow(new EmptyResultDataAccessException(1));
        when(jdbcTemplate.update(contains("UPDATE cortex_evento_commit_sequence")))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(
                eq("SELECT LAST_INSERT_ID()"),
                eq(Long.class)
        )).thenReturn(7L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT sequencia"),
                eq(Long.class),
                eq("evento-1")
        )).thenReturn(42L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT versao_entidade"),
                eq(Long.class),
                eq("EQUIPE"),
                eq("equipe-1")
        )).thenReturn(3L);

        long commitSeq = service.registrarEventoDetalhado(
                "evento-1",
                "EQUIPE",
                "equipe-1",
                "EQUIPE_ATUALIZADA",
                "GESTAO_EQUIPE",
                "obra-1",
                null,
                null,
                List.of(Map.of("tipo", "OBRA", "id", "obra-1")),
                "ONLINE",
                "SYNCED",
                now,
                now,
                1,
                Map.of("schemaVersion", 1)
        );

        assertThat(commitSeq).isEqualTo(7);
        verify(jdbcTemplate).update(
                contains("SET versao_entidade = ?"),
                eq(3L),
                eq("evento-1")
        );
    }

    @Test
    void enrichesEveryEventWithActiveOfflineTraceContext() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CortexOperationalMemoryService service = new CortexOperationalMemoryService(
                jdbcTemplate,
                new ObjectMapper(),
                publisher
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 14, 45);

        when(jdbcTemplate.queryForObject(
                contains("SELECT commit_seq"),
                eq(Long.class),
                eq("evento-offline")
        )).thenThrow(new EmptyResultDataAccessException(1));
        when(jdbcTemplate.update(contains("UPDATE cortex_evento_commit_sequence")))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(
                eq("SELECT LAST_INSERT_ID()"),
                eq(Long.class)
        )).thenReturn(8L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT sequencia"),
                eq(Long.class),
                eq("evento-offline")
        )).thenReturn(43L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT versao_entidade"),
                eq(Long.class),
                eq("EQUIPE"),
                eq("equipe-1")
        )).thenReturn(4L);

        try (OperationalEventTraceContext.Scope ignored =
                     OperationalEventTraceContext.openOffline(
                             "device-1",
                             "alfa-1",
                             "mutation-1"
                     )) {
            service.registrarEventoDetalhado(
                    "evento-offline",
                    "EQUIPE",
                    "equipe-1",
                    "EQUIPE_ATUALIZADA",
                    "GESTAO_EQUIPE",
                    "obra-1",
                    null,
                    null,
                    List.of(),
                    "ONLINE",
                    "SYNCED",
                    now,
                    now,
                    1,
                    Map.of("actorId", "alfa-1")
            );
        }

        verify(jdbcTemplate).update(
                contains("INSERT INTO cortex_evento_operacional"),
                eq("evento-offline"),
                eq(8L),
                eq("EQUIPE"),
                eq("equipe-1"),
                eq("obra-1"),
                eq(null),
                eq(null),
                eq("EQUIPE_ATUALIZADA"),
                eq("GESTAO_EQUIPE"),
                eq("OFFLINE"),
                eq("SYNCED"),
                eq(now),
                eq("alfa-1"),
                eq("device-1"),
                eq("[]"),
                eq(1),
                argThat((String json) -> json.contains("\"correlationId\":\"mutation-1\"")
                        && json.contains("\"deviceId\":\"device-1\""))
        );
        assertThat(OperationalEventTraceContext.current()).isEmpty();
    }
}
