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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A lista de obras da Home e do RDO.
 *
 * <p>Ela já filtrou por vínculo, deixou de filtrar, e volta a filtrar: quem é
 * Beta enxerga apenas as obras em que trabalha. A ida e a volta são a razão de
 * estes testes olharem o SQL e não só o resultado — a cerca já saiu daqui uma
 * vez sem que nada ficasse vermelho, e é isso que não pode se repetir.
 *
 * <p>O que cada teste protege: que Alfa não receba condição de vínculo, que Beta
 * receba, e que a comparação de {@code colaborador_id} seja por {@code LOWER}
 * dos dois lados. Esta última é a que evita o pior desfecho — o colaborador
 * autorizado na obra que mesmo assim não a vê na lista.
 */
class ObrasRelacionadasServiceTest {

    private record Montagem(
            JdbcTemplate jdbc,
            CurrentUserService users,
            FinancialAccessService financial,
            ObrasRelacionadasService service
    ) {
    }

    private static Montagem montarPara(String userId, boolean alfa) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService users = mock(CurrentUserService.class);
        FinancialAccessService financial = mock(FinancialAccessService.class);
        when(users.requireUserId()).thenReturn(userId);
        when(users.isAlfa(userId)).thenReturn(alfa);
        when(financial.allowedObraIds(anyString(), any())).thenReturn(Set.of());
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        return new Montagem(
                jdbc,
                users,
                financial,
                new ObrasRelacionadasService(jdbc, users, financial)
        );
    }

    @Test
    void betaRecebeAConsultaRecortadaPeloVinculoAtivo() {
        Montagem montagem = montarPara("colab-1", false);

        montagem.service().listarParaColaborador();

        verify(montagem.jdbc()).query(
                argThat((String sql) ->
                        sql.contains("vinculo_colaborador_obra")
                                && sql.contains("v.status = 'ATIVO'")
                                && sql.contains("o.arquivado_em IS NULL")
                ),
                any(RowMapper.class),
                eq("colab-1")
        );
    }

    /**
     * O defeito que a caixa causa é silencioso: a pessoa passa na autorização da
     * obra e mesmo assim não a encontra na lista. As duas pontas comparam por
     * {@code LOWER}, e é aqui que isso fica escrito.
     */
    @Test
    void oVinculoEComparadoSemDiferenciarCaixaNosDoisLados() {
        Montagem montagem = montarPara("Colab-1", false);

        montagem.service().listarParaColaborador();

        verify(montagem.jdbc()).query(
                argThat((String sql) ->
                        sql.contains("LOWER(v.colaborador_id) = LOWER(?)")),
                any(RowMapper.class),
                eq("Colab-1")
        );
    }

    @Test
    void alfaNaoRecebeCondicaoDeVinculoNemParametro() {
        Montagem montagem = montarPara("alfa-1", true);

        montagem.service().listarParaColaborador();

        verify(montagem.jdbc()).query(
                argThat((String sql) -> sql.contains("o.versao_linha")
                        && !sql.contains("vinculo_colaborador_obra")),
                any(RowMapper.class)
        );
    }

    @Test
    void projecaoRelacionadaCarregaVersaoAutoritativa() throws Exception {
        Montagem montagem = montarPara("alfa-1", true);
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
