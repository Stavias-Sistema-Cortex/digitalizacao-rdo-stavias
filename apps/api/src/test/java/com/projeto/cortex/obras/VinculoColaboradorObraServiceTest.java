package com.projeto.cortex.obras;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica que cada transição de vínculo gera evento ontológico e mantém a
 * relação no grafo, e que revincular um vínculo já ativo é idempotente.
 */
class VinculoColaboradorObraServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CortexOperationalMemoryService memory =
            mock(CortexOperationalMemoryService.class);
    private final ObraOperabilityGuard operabilityGuard =
            mock(ObraOperabilityGuard.class);
    private final VinculoColaboradorObraService service =
            new VinculoColaboradorObraService(
                    jdbc,
                    memory,
                    operabilityGuard
            );

    private void obraExiste() {
        when(jdbc.queryForObject(
                contains("SELECT 1 FROM obra WHERE id = ?"),
                eq(Integer.class),
                anyString()
        )).thenReturn(1);
    }

    private void colaboradorExiste() {
        when(jdbc.queryForObject(
                contains("ativo = TRUE"),
                eq(Integer.class),
                anyString()
        )).thenReturn(1);
    }

    private void vinculoVigente(VinculoColaboradorObraService.VinculoAtual atual) {
        when(jdbc.query(
                contains("WHERE colaborador_id"),
                any(RowMapper.class),
                eq("colab-1"),
                eq("obra-1")
        )).thenReturn(atual == null ? List.of() : List.of(atual));
    }

    private void carregaResposta() {
        when(jdbc.queryForObject(
                contains("WHERE v.id"),
                any(RowMapper.class),
                anyString()
        )).thenReturn(new VinculoColaboradorObraResponse(
                "vinc-1", "obra-1", "colab-1", "Fulano",
                "ATIVO", "OPERACIONAL", LocalDateTime.now(), "alfa", null, null
        ));
    }

    @Test
    void vincularNovoGeraEventoOntologicoERelacao() {
        obraExiste();
        colaboradorExiste();
        vinculoVigente(null);
        carregaResposta();

        service.vincular("obra-1", "colab-1", "OPERACIONAL", "alfa");

        verify(operabilityGuard).requireWritable("obra-1");
        verify(jdbc).update(contains("INSERT INTO vinculo_colaborador_obra"),
                any(), any(), any(), any(), any(), any());
        verify(memory).registrarEventoDetalhado(
                any(), eq("VINCULO_OBRA"), any(),
                eq("VINCULO_OBRA_ATRIBUIDO"), eq("GESTAO_VINCULO"),
                eq("obra-1"), isNull(), eq("colab-1"),
                any(), eq("ONLINE"), eq("SYNCED"),
                any(), any(), eq(1), any()
        );
        verify(memory).registrarRelacaoAtiva(
                eq("COLABORADOR"), eq("colab-1"),
                eq("OBRA"), eq("obra-1"),
                eq("VINCULADO_A"), eq("GESTAO_VINCULO"), anyString()
        );
    }

    @Test
    void revincularAtivoEhIdempotenteSemNovoEvento() {
        vinculoVigente(new VinculoColaboradorObraService.VinculoAtual(
                "vinc-1", "ATIVO", "OPERACIONAL"
        ));
        carregaResposta();

        service.vincular("obra-1", "colab-1", "OPERACIONAL", "alfa");

        verify(operabilityGuard, never()).requireWritable(anyString());
        verify(jdbc, never()).update(
                contains("INSERT INTO vinculo_colaborador_obra"),
                any(), any(), any(), any(), any(), any()
        );
        verify(memory, never()).registrarEventoDetalhado(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void revogarGeraEventoEEncerraRelacao() {
        obraExiste();
        vinculoVigente(new VinculoColaboradorObraService.VinculoAtual(
                "vinc-1", "ATIVO", "OPERACIONAL"
        ));
        carregaResposta();

        service.revogar("obra-1", "colab-1", "alfa");

        verify(jdbc).update(contains("SET status = 'REVOGADO'"),
                any(), any(), any());
        verify(memory).registrarEventoDetalhado(
                any(), eq("VINCULO_OBRA"), any(),
                eq("VINCULO_OBRA_REVOGADO"), eq("GESTAO_VINCULO"),
                eq("obra-1"), isNull(), eq("colab-1"),
                any(), eq("ONLINE"), eq("SYNCED"),
                any(), any(), eq(1), any()
        );
        verify(memory).encerrarRelacaoAtiva(
                eq("COLABORADOR"), eq("colab-1"),
                eq("OBRA"), eq("obra-1"), eq("VINCULADO_A")
        );
        verify(operabilityGuard, never()).requireWritable(anyString());
    }

    @Test
    void listarHistoricoNaoAplicaFiltroDeArquivamento() {
        obraExiste();
        when(jdbc.query(
                contains("WHERE v.obra_id = ?"),
                any(RowMapper.class),
                eq("obra-1")
        )).thenReturn(List.of());

        service.listarPorObra("obra-1");

        verify(jdbc).queryForObject(
                argThat(sql -> sql.contains("SELECT 1 FROM obra WHERE id = ?")
                        && !sql.contains("arquivado_em")),
                eq(Integer.class),
                eq("obra-1")
        );
        verify(operabilityGuard, never()).requireWritable(anyString());
    }
}
