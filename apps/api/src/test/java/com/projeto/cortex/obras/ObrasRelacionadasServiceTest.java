package com.projeto.cortex.obras;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObrasRelacionadasServiceTest {

    @Test
    void colaboradorComumFiltraPorVinculo() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService users = mock(CurrentUserService.class);
        FinancialAccessService financial = mock(FinancialAccessService.class);
        when(users.requireUserId()).thenReturn("colab-1");
        when(users.isAdmin("colab-1")).thenReturn(false);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        ObrasRelacionadasService service =
                new ObrasRelacionadasService(jdbc, users, financial);
        List<ObraRelacionadaResponse> result = service.listarParaColaborador();

        assertTrue(result.isEmpty());
        verify(jdbc).query(
                anyString(),
                any(RowMapper.class),
                eq(0), eq("colab-1")
        );
    }

    @Test
    void adminVeTodasAsObras() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService users = mock(CurrentUserService.class);
        FinancialAccessService financial = mock(FinancialAccessService.class);
        when(users.requireUserId()).thenReturn("admin-1");
        when(users.isAdmin("admin-1")).thenReturn(true);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        new ObrasRelacionadasService(jdbc, users, financial)
                .listarParaColaborador();

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(
                        sql -> sql.contains("o.versao_linha")
                ),
                any(RowMapper.class),
                eq(1), eq("admin-1")
        );
    }

    @Test
    void projecaoRelacionadaCarregaVersaoAutoritativa() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService users = mock(CurrentUserService.class);
        FinancialAccessService financial = mock(FinancialAccessService.class);
        ResultSet row = mock(ResultSet.class);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 28, 19, 55);

        when(users.requireUserId()).thenReturn("admin-1");
        when(users.isAdmin("admin-1")).thenReturn(true);
        when(financial.allowedObraIds(anyString(), any())).thenReturn(Set.of());
        when(row.getString("id")).thenReturn("obra-1");
        when(row.getString("codigo_contrato")).thenReturn("CT-1");
        when(row.getString("nome")).thenReturn("Obra 1");
        when(row.getString("status")).thenReturn("ATIVA");
        when(row.getTimestamp("atualizado_em"))
                .thenReturn(Timestamp.valueOf(updatedAt));
        when(row.getLong("versao_linha")).thenReturn(7L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<ObraRelacionadaResponse> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        List<ObraRelacionadaResponse> result =
                new ObrasRelacionadasService(jdbc, users, financial)
                        .listarParaColaborador();

        assertEquals(1, result.size());
        assertEquals(7L, result.getFirst().versaoLinha());
        assertEquals(updatedAt, result.getFirst().atualizadoEm());
    }
}
