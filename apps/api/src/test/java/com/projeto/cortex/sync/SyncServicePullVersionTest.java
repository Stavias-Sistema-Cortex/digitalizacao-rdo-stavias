package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncServicePullVersionTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeEntityVersionInPullEvents() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CurrentUserService currentUserService =
                mock(CurrentUserService.class);
        when(currentUserService.requireUserId())
                .thenReturn("usuario-auth");

        // dispositivo pertence ao usuário
        when(
                jdbcTemplate.queryForObject(
                        anyString(),
                        eq(Integer.class),
                        eq("device-1"),
                        eq("usuario-auth")
                )
        ).thenReturn(1);

        ArgumentCaptor<String> sqlCaptor =
                ArgumentCaptor.forClass(String.class);

        when(
                jdbcTemplate.query(
                        sqlCaptor.capture(),
                        any(RowMapper.class),
                        eq(0L),
                        anyInt()
                )
        ).thenAnswer(invocation -> {
            RowMapper<SyncPullResponse.EventoSync> mapper =
                    invocation.getArgument(1);

            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("commit_seq")).thenReturn(10L);
            when(resultSet.getString("id")).thenReturn("ev-1");
            when(resultSet.getString("tipo_entidade")).thenReturn("RDO");
            when(resultSet.getString("entidade_id")).thenReturn("rdo-1");
            when(resultSet.getString("tipo_evento"))
                    .thenReturn("RDO_ENVIADO");
            when(resultSet.getString("fonte")).thenReturn("SISTEMA");
            when(resultSet.getString("payload_json")).thenReturn("{}");
            when(resultSet.getTimestamp("ocorrido_em")).thenReturn(
                    Timestamp.valueOf(
                            LocalDateTime.of(2026, 7, 2, 12, 0)
                    )
            );
            when(resultSet.getTimestamp("criado_em")).thenReturn(
                    Timestamp.valueOf(
                            LocalDateTime.of(2026, 7, 2, 12, 0)
                    )
            );
            when(resultSet.getObject("versao_entidade", Long.class))
                    .thenReturn(7L);

            return List.of(mapper.mapRow(resultSet, 0));
        });

        SyncService service = new SyncService(
                jdbcTemplate,
                new ObjectMapper(),
                mock(TransactionTemplate.class),
                currentUserService,
                List.of()
        );

        SyncPullResponse response =
                service.pull("device-1", 0L, 10);

        assertThat(sqlCaptor.getValue()).contains("versao_entidade");
        assertThat(response.eventos()).hasSize(1);
        assertThat(response.eventos().getFirst().versaoEntidade())
                .isEqualTo(7L);
    }
}
