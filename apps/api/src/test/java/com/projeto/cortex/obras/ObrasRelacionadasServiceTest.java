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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A lista de obras da Home e do RDO.
 *
 * <p>Ela filtrava por {@code vinculo_colaborador_obra}, e quem não estivesse
 * vinculado não via a obra — nem para consultar. Deixou de filtrar: a consulta
 * é a mesma para todo mundo, e por isso não recebe mais nenhum parâmetro. É
 * disso que estes testes tomam conta, porque um parâmetro reintroduzido aqui
 * seria uma cerca voltando sem alarde.
 */
class ObrasRelacionadasServiceTest {

    private record Montagem(
            JdbcTemplate jdbc,
            CurrentUserService users,
            FinancialAccessService financial,
            ObrasRelacionadasService service
    ) {
    }

    private static Montagem montarPara(String userId, boolean admin) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService users = mock(CurrentUserService.class);
        FinancialAccessService financial = mock(FinancialAccessService.class);
        when(users.requireUserId()).thenReturn(userId);
        when(users.isAdmin(userId)).thenReturn(admin);
        when(financial.allowedObraIds(anyString(), any())).thenReturn(Set.of());
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of());
        return new Montagem(
                jdbc,
                users,
                financial,
                new ObrasRelacionadasService(jdbc, users, financial)
        );
    }

    /**
     * O caso que motivou a mudança: quem não tem vínculo nenhum recebe a mesma
     * consulta que o administrador, sem condição de vínculo e sem parâmetro que
     * pudesse recortá-la por pessoa.
     */
    @Test
    void colaboradorSemVinculoRecebeAConsultaSemRecorte() {
        Montagem montagem = montarPara("colab-1", false);

        montagem.service().listarParaColaborador();

        verify(montagem.jdbc()).query(
                argThat((String sql) ->
                        !sql.contains("vinculo_colaborador_obra")
                                && sql.contains("o.arquivado_em IS NULL")
                ),
                any(RowMapper.class)
        );
    }

    @Test
    void adminRecebeExatamenteAMesmaConsulta() {
        Montagem montagem = montarPara("admin-1", true);

        montagem.service().listarParaColaborador();

        verify(montagem.jdbc()).query(
                argThat((String sql) -> sql.contains("o.versao_linha")
                        && !sql.contains("vinculo_colaborador_obra")),
                any(RowMapper.class)
        );
    }

    @Test
    void projecaoRelacionadaCarregaVersaoAutoritativa() throws Exception {
        Montagem montagem = montarPara("admin-1", true);
        ResultSet row = mock(ResultSet.class);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 28, 19, 55);

        when(row.getString("id")).thenReturn("obra-1");
        when(row.getString("codigo_contrato")).thenReturn("CT-1");
        when(row.getString("nome")).thenReturn("Obra 1");
        when(row.getString("status")).thenReturn("ATIVA");
        when(row.getTimestamp("atualizado_em"))
                .thenReturn(Timestamp.valueOf(updatedAt));
        when(row.getLong("versao_linha")).thenReturn(7L);
        when(montagem.jdbc().query(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<ObraRelacionadaResponse> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        List<ObraRelacionadaResponse> result =
                montagem.service().listarParaColaborador();

        assertEquals(1, result.size());
        assertEquals(7L, result.getFirst().versaoLinha());
        assertEquals(updatedAt, result.getFirst().atualizadoEm());
    }
}
