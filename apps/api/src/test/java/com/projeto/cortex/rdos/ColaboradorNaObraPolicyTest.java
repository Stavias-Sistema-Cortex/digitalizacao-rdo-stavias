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
 * O diário registra o que aconteceu; o vínculo descreve quem a obra reconhece
 * hoje. São duas perguntas diferentes, e uma deixou de poder anular a resposta
 * da outra.
 */
class ColaboradorNaObraPolicyTest {

    private static final String COLABORADOR = "colaborador-1";
    private static final String OBRA = "obra-1";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private void colaboradorAtivo(boolean ativo) {
        when(jdbc.queryForObject(
                contains("FROM colaborador collaborator"),
                eq(Integer.class),
                eq(COLABORADOR)
        )).thenReturn(ativo ? 1 : 0);
    }

    @Test
    void quemPerdeuOVinculoContinuaPodendoConstarNoRdo() {
        colaboradorAtivo(true);

        assertThatCode(() ->
                ColaboradorNaObraPolicy.require(jdbc, COLABORADOR, OBRA)
        ).doesNotThrowAnyException();
    }

    /**
     * A exigência caiu nos dois caminhos, não só na convergência do sync: o
     * apontamento é o mesmo fato, tenha chegado pela fila ou pela tela.
     */
    @Test
    void oVinculoNaoEConsultadoEmCaminhoNenhum() {
        colaboradorAtivo(true);

        ColaboradorNaObraPolicy.require(jdbc, COLABORADOR, OBRA);
        try (SyncConvergenceWindow ignored = SyncConvergenceWindow.open()) {
            ColaboradorNaObraPolicy.require(jdbc, COLABORADOR, OBRA);
        }

        verify(jdbc, never()).queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                any(),
                any()
        );
    }

    /**
     * A metade que não cede. Afrouxar a autorização foi a decisão tomada; abrir
     * mão da referência seria outra. Sem colaborador existente não há a quem
     * atribuir o trabalho, e gravar um identificador solto criaria mão de obra
     * que não corresponde a ninguém — o RDO deixaria de ser auditável.
     */
    @Test
    void colaboradorInexistenteOuInativoSegueRecusado() {
        colaboradorAtivo(false);

        assertThatThrownBy(() ->
                ColaboradorNaObraPolicy.require(jdbc, COLABORADOR, OBRA)
        )
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Colaborador não encontrado ou inativo.");
    }
}
