package com.projeto.cortex.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(jdbcTemplate.queryForObject(
                contains("RETURNING ultima_commit_seq"),
                eq(Long.class)
        )).thenReturn(7L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT sequencia"),
                eq(Long.class),
                eq("evento-1")
        )).thenReturn(42L);
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
        ArgumentCaptor<Object[]> eventInsertParameters =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("INSERT INTO cortex_evento_operacional")
                        && sql.contains("ocorrido_em")),
                eventInsertParameters.capture()
        );
        assertThat(eventInsertParameters.getValue()).contains(now);
        verify(jdbcTemplate, never()).update(
                contains("UPDATE cortex_evento_operacional"),
                any(Object[].class)
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO cortex_estado_entidade"),
                eq("EQUIPE"),
                eq("equipe-1"),
                eq(42L)
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
        when(jdbcTemplate.queryForObject(
                contains("RETURNING ultima_commit_seq"),
                eq(Long.class)
        )).thenReturn(8L);
        when(jdbcTemplate.queryForObject(
                contains("SELECT sequencia"),
                eq(Long.class),
                eq("evento-offline")
        )).thenReturn(43L);
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

        ArgumentCaptor<Object[]> insertParameters =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                contains("INSERT INTO cortex_evento_operacional"),
                insertParameters.capture()
        );
        assertThat(insertParameters.getValue()).containsSubsequence(
                "alfa-1",
                "device-1",
                "mutation-1",
                "mutation-1",
                "OFFLINE",
                "SYNCED"
        );
        Object[] parameters = insertParameters.getValue();
        assertThat(String.valueOf(parameters[parameters.length - 1]))
                .contains("\"correlationId\":\"mutation-1\"")
                .contains("\"deviceId\":\"device-1\"");
        assertThat(OperationalEventTraceContext.current()).isEmpty();
    }
}
