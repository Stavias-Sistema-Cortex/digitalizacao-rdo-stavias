package com.projeto.cortex.colaboradores;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A lista que o reparo consulta antes de apagar alguém de um RDO.
 *
 * <p>Ela não monta tela. Quando uma mutação volta recusada por vínculo, o
 * aparelho pergunta aqui quais identificadores ainda valem e remove os que não
 * valem — então uma resposta estreita demais não esconde alguém, ela apaga
 * alguém de um apontamento já feito.
 */
class ColaboradorDaObraServiceTest {

    /*
     * A consulta deixou de olhar o vínculo com a obra. Ela era mais estreita até
     * do que a lista antiga da tela, que já somava as equipes, e por isso já
     * apagava em silêncio quem entrou na obra por equipe.
     */
    @Test
    void aceitaQuemEstaAtivoNoQuadro() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                contains("count(*)"),
                eq(Integer.class)
        )).thenReturn(2);
        when(jdbc.queryForList(
                contains("LIMIT 5001"),
                eq(String.class)
        )).thenReturn(List.of("col-1", "col-2"));

        ColaboradoresAutorizadosObraResponse response =
                new ColaboradorDaObraService(jdbc)
                        .listarAutorizados("obra-1");

        assertThat(response.ids()).containsExactly("col-1", "col-2");
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.complete()).isTrue();
    }

    /** Quem saiu do quadro é o vínculo que de fato acabou. */
    @Test
    void continuaExigindoCadastroAtivoENaoApagado() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("count(*)"), eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.queryForList(contains("LIMIT 5001"), eq(String.class)))
                .thenReturn(List.of());

        new ColaboradorDaObraService(jdbc).listarAutorizados("obra-1");

        verify(jdbc).queryForObject(
                contains("c.ativo = TRUE"),
                eq(Integer.class)
        );
        verify(jdbc).queryForList(
                contains("c.deletado_em IS NULL"),
                eq(String.class)
        );
    }

    /*
     * Cobertura incompleta faz o aparelho recusar o reparo em vez de apagar por
     * engano. O teto subiu junto com o escopo: calibrado para uma obra, ele
     * recusaria todo reparo numa empresa inteira.
     */
    @Test
    void sinalizaCoberturaIncompletaNoTeto() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String> sentinela = IntStream.rangeClosed(1, 5001)
                .mapToObj(index -> "col-" + index)
                .toList();
        when(jdbc.queryForObject(
                contains("count(*)"),
                eq(Integer.class)
        )).thenReturn(5001);
        when(jdbc.queryForList(
                contains("LIMIT 5001"),
                eq(String.class)
        )).thenReturn(sentinela);

        ColaboradoresAutorizadosObraResponse response =
                new ColaboradorDaObraService(jdbc)
                        .listarAutorizados("obra-2");

        assertThat(response.ids()).hasSize(5000);
        assertThat(response.total()).isEqualTo(5001);
        assertThat(response.complete()).isFalse();
    }
}
