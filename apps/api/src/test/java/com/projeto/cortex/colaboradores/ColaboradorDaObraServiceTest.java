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

class ColaboradorDaObraServiceTest {

    @Test
    void retornaCoberturaCompletaSomenteParaVinculosAtivosExatos() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                contains("count(DISTINCT c.id)"),
                eq(Integer.class),
                eq("obra-1")
        )).thenReturn(2);
        when(jdbc.queryForList(
                contains("LIMIT 501"),
                eq(String.class),
                eq("obra-1")
        )).thenReturn(List.of("col-1", "col-2"));

        ColaboradoresAutorizadosObraResponse response =
                new ColaboradorDaObraService(jdbc)
                        .listarAutorizados("obra-1");

        assertThat(response.ids()).containsExactly("col-1", "col-2");
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.complete()).isTrue();
        verify(jdbc).queryForObject(
                contains("link.status = 'ATIVO'"),
                eq(Integer.class),
                eq("obra-1")
        );
        verify(jdbc).queryForList(
                contains("c.deletado_em IS NULL"),
                eq(String.class),
                eq("obra-1")
        );
    }

    @Test
    void sinalizaCoberturaIncompletaComSentinela501() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String> sentinel = IntStream.rangeClosed(1, 501)
                .mapToObj(index -> "col-" + index)
                .toList();
        when(jdbc.queryForObject(
                contains("count(DISTINCT c.id)"),
                eq(Integer.class),
                eq("obra-2")
        )).thenReturn(501);
        when(jdbc.queryForList(
                contains("LIMIT 501"),
                eq(String.class),
                eq("obra-2")
        )).thenReturn(sentinel);

        ColaboradoresAutorizadosObraResponse response =
                new ColaboradorDaObraService(jdbc)
                        .listarAutorizados("obra-2");

        assertThat(response.ids()).hasSize(500);
        assertThat(response.total()).isEqualTo(501);
        assertThat(response.complete()).isFalse();
    }
}
