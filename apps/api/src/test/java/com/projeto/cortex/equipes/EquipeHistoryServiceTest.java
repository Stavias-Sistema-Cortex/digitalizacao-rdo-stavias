package com.projeto.cortex.equipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EquipeHistoryServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EquipeService equipeService = mock(EquipeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EquipeHistoryService service = new EquipeHistoryService(
            jdbcTemplate,
            objectMapper,
            equipeService
    );

    @Test
    void authorizesTeamBeforeReadingOperationalEvents() {
        ResponseStatusException forbidden = new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Acesso negado à equipe."
        );
        when(equipeService.buscarPorId("equipe-1")).thenThrow(forbidden);

        assertThatThrownBy(() -> service.listar("equipe-1", 0L, 50))
                .isSameAs(forbidden);

        verify(jdbcTemplate, never()).query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
        );
    }

    @Test
    void returnsCommitCursorAndKeepsOverflowRowForNextPage() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 10, 30);
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-1", "Obra Norte", "Equipe Norte",
                null, "ATIVA", now, null, 3, now, now, List.of()
        );
        EquipeHistoryResponse first = event(11, "evento-11", now);
        EquipeHistoryResponse second = event(12, "evento-12", now.plusMinutes(1));
        EquipeHistoryResponse overflow = event(13, "evento-13", now.plusMinutes(2));

        when(equipeService.buscarPorId("equipe-1")).thenReturn(team);
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<EquipeHistoryResponse>>any(),
                eq(10L),
                eq("equipe-1"),
                eq("equipe-1"),
                eq(3)
        )).thenReturn(List.of(first, second, overflow));

        EquipeHistoryPageResponse response = service.listar("equipe-1", 10L, 2);

        assertThat(response.afterCommitSeq()).isEqualTo(10);
        assertThat(response.nextCommitSeq()).isEqualTo(12);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.limit()).isEqualTo(2);
        assertThat(response.events()).containsExactly(first, second);
    }

    private EquipeHistoryResponse event(
            long commitSeq,
            String eventId,
            LocalDateTime occurredAt
    ) {
        return new EquipeHistoryResponse(
                commitSeq,
                eventId,
                "EQUIPE",
                "equipe-1",
                "EQUIPE_ATUALIZADA",
                "GESTAO_EQUIPE",
                "obra-1",
                null,
                occurredAt,
                occurredAt,
                commitSeq,
                objectMapper.createObjectNode()
        );
    }
}
