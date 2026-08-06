package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import com.projeto.cortex.auth.CurrentUserService;

/**
 * As recusas, e o que elas garantem: que nada foi apagado antes.
 *
 * <p>Plantar uma evidência de receita de verdade exigiria serviço, versão de
 * preço vigente e evento — fixture profundo demais para provar uma recusa. O
 * gatilho do banco é a garantia final; o que interessa aqui é que o serviço
 * recuse ANTES, com uma frase que diga o motivo, em vez de deixar a transação
 * estourar no meio com erro de banco e o RDO pela metade.
 */
class RdoDeletionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentUserService currentUserService =
            mock(CurrentUserService.class);
    private final RdoMemoryPublisher memoryPublisher =
            mock(RdoMemoryPublisher.class);
    private final RdoDeletionService service = new RdoDeletionService(
            jdbcTemplate, currentUserService, memoryPublisher
    );

    @BeforeEach
    void alfaAutenticado() {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
    }

    /** Faz o `query` devolver um alvo real, passando pelo RowMapper do serviço. */
    @SuppressWarnings("unchecked")
    private void existeRdo(String status) {
        when(jdbcTemplate.query(
                contains("FROM rdo WHERE id"),
                any(RowMapper.class),
                eq("rdo-1")
        )).thenAnswer(chamada -> {
            RowMapper<Object> mapeador = chamada.getArgument(1);
            ResultSet linha = mock(ResultSet.class);
            when(linha.getString("id")).thenReturn("rdo-1");
            when(linha.getString("obra_id")).thenReturn("obra-1");
            when(linha.getString("numero_rdo")).thenReturn("RDO-0007");
            when(linha.getString("status")).thenReturn(status);
            return List.of(mapeador.mapRow(linha, 0));
        });
    }

    @SuppressWarnings("unchecked")
    private void semDependentes() {
        when(jdbcTemplate.query(
                contains("WHERE previous_rdo_id"),
                any(RowMapper.class),
                eq("rdo-1")
        )).thenReturn(List.of());
    }

    private void servicosMedidos(int quantos) {
        when(jdbcTemplate.queryForObject(
                contains("revenue_evidence_id IS NOT NULL"),
                eq(Integer.class),
                eq("rdo-1")
        )).thenReturn(quantos);
    }

    /*
     * A frase importa tanto quanto a recusa. "Erro ao apagar" mandaria a pessoa
     * tentar de novo; dizer que a medição é registro financeiro encerra o
     * assunto e explica por quê.
     */
    @Test
    void recusaRdoComServicoMedidoSemApagarNada() {
        existeRdo("RASCUNHO");
        servicosMedidos(2);

        assertThatThrownBy(() -> service.apagar("rdo-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("2 serviços já medidos")
                .hasMessageContaining("registro financeiro");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(memoryPublisher, never()).registrarRdoApagado(
                anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void concordaOSingularQuandoEUmSo() {
        existeRdo("RASCUNHO");
        servicosMedidos(1);

        assertThatThrownBy(() -> service.apagar("rdo-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("1 serviço já medido");
    }

    /* Rascunho não passa pelo portão do Alfa: quem monta desfaz. */
    @Test
    void rascunhoNaoExigeAlfa() {
        existeRdo("RASCUNHO");
        servicosMedidos(0);
        semDependentes();
        when(jdbcTemplate.update(contains("DELETE FROM rdo WHERE id"), eq("rdo-1")))
                .thenReturn(1);

        assertThatCode(() -> service.apagar("rdo-1")).doesNotThrowAnyException();

        verify(currentUserService, never()).requireAlfa();
        verify(currentUserService).requireWorksiteAccess("obra-1");
    }

    /* Enviado é registro entregue; desfazer entrega é decisão de quem responde. */
    @Test
    void enviadoExigeAlfa() {
        existeRdo("ENVIADO");
        servicosMedidos(0);
        semDependentes();
        when(jdbcTemplate.update(contains("DELETE FROM rdo WHERE id"), eq("rdo-1")))
                .thenReturn(1);

        service.apagar("rdo-1");

        verify(currentUserService).requireAlfa();
    }

    /*
     * Se o DELETE final não pegou exatamente uma linha, alguém mexeu no RDO
     * no meio do caminho. Seguir em frente publicaria na Memória um
     * apagamento que não aconteceu.
     */
    @Test
    void naoPublicaQuandoODeleteFinalNaoPegaExatamenteUmaLinha() {
        existeRdo("RASCUNHO");
        servicosMedidos(0);
        semDependentes();
        when(jdbcTemplate.update(contains("DELETE FROM rdo WHERE id"), eq("rdo-1")))
                .thenReturn(0);

        assertThatThrownBy(() -> service.apagar("rdo-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("mudou durante a exclusão");

        verify(memoryPublisher, never()).registrarRdoApagado(
                anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void exigeIdentificador() {
        assertThatThrownBy(() -> service.apagar("  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rdoId é obrigatório");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }
}
