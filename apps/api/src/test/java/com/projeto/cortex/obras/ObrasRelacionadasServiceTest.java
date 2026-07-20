package com.projeto.cortex.obras;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

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
                anyString(),
                any(RowMapper.class),
                eq(1), eq("admin-1")
        );
    }
}
