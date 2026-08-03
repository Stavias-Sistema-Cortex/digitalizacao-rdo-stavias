package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.common.SyncConvergenceWindow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * O apontamento vem do campo, às vezes de um aparelho que passou horas sem
 * rede. O vínculo pode ter sido revogado nesse intervalo, e a pessoa trabalhou
 * assim mesmo — recusar a escrita não desfaz o que foi vivido, só prende o
 * registro no aparelho.
 */
class ColaboradorNaObraPolicyTest {

    private static final String COLABORADOR = "colaborador-1";
    private static final String OBRA = "obra-1";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void oCaminhoInterativoSegueExigindoVinculo() {
        when(jdbc.queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                eq(OBRA),
                eq(COLABORADOR)
        )).thenReturn(0);

        assertThatThrownBy(() ->
                ColaboradorNaObraPolicy.require(jdbc, COLABORADOR, OBRA)
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "Colaborador não está ativo e vinculado à obra do RDO."
                );
    }

    @Test
    void aConvergenciaAceitaQuemPerdeuOVinculo() {
        when(jdbc.queryForObject(
                contains("FROM colaborador collaborator"),
                eq(Integer.class),
                eq(COLABORADOR)
        )).thenReturn(1);

        try (SyncConvergenceWindow ignored = SyncConvergenceWindow.open()) {
            assertThatCode(() ->
                    ColaboradorNaObraPolicy.require(jdbc, COLABORADOR, OBRA)
            ).doesNotThrowAnyException();
        }

        // O vínculo não chega a ser consultado: deixou de ser exigência.
        verify(jdbc, never()).queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                any(),
                any()
        );
    }

    /**
     * A metade que não cede. Afrouxar a autorização é a decisão tomada; abrir
     * mão da referência seria outra. Sem colaborador existente não há a quem
     * atribuir o trabalho, e gravar um identificador solto criaria mão de obra
     * que não corresponde a ninguém.
     */
    @Test
    void aConvergenciaAindaRecusaColaboradorInexistenteOuInativo() {
        when(jdbc.queryForObject(
                contains("FROM colaborador collaborator"),
                eq(Integer.class),
                eq(COLABORADOR)
        )).thenReturn(0);

        try (SyncConvergenceWindow ignored = SyncConvergenceWindow.open()) {
            assertThatThrownBy(() ->
                    ColaboradorNaObraPolicy.require(jdbc, COLABORADOR, OBRA)
            )
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Colaborador não encontrado ou inativo.");
        }
    }

    @Test
    void aJanelaNaoVazaParaAsRequisicoesSeguintes() {
        try (SyncConvergenceWindow ignored = SyncConvergenceWindow.open()) {
            // apenas abre e fecha
        }
        when(jdbc.queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                eq(OBRA),
                eq(COLABORADOR)
        )).thenReturn(0);

        assertThatThrownBy(() ->
                ColaboradorNaObraPolicy.require(jdbc, COLABORADOR, OBRA)
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("vinculado à obra do RDO");
    }
}
