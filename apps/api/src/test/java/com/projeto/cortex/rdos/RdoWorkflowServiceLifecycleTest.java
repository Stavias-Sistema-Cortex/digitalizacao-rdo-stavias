package com.projeto.cortex.rdos;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdoWorkflowServiceLifecycleTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RdoQueryService queryService = mock(RdoQueryService.class);
    private final RdoMemoryPublisher memoryPublisher =
            mock(RdoMemoryPublisher.class);
    private final PrevisaoFinanceiraService previsao =
            mock(PrevisaoFinanceiraService.class);
    private final ObraOperabilityGuard guard =
            mock(ObraOperabilityGuard.class);
    private final RdoWorkflowService service = new RdoWorkflowService(
            jdbc,
            queryService,
            memoryPublisher,
            previsao,
            guard
    );

    private void canceladoEm(boolean cancelado) {
        when(jdbc.queryForObject(
                contains("cancelado_em IS NOT NULL"),
                eq(Boolean.class),
                eq("rdo-1")
        )).thenReturn(cancelado);
    }

    private RdoResponse respostaCanonica(String status) {
        RdoResponse response = mock(RdoResponse.class);
        when(response.obraId()).thenReturn("obra-1");
        when(response.programacaoId()).thenReturn(null);
        when(response.numeroRdo()).thenReturn("RDO-014");
        when(response.dataRdo()).thenReturn(LocalDate.of(2026, 8, 1));
        when(response.status()).thenReturn(status);
        when(queryService.buscarPorId("rdo-1")).thenReturn(response);
        return response;
    }

    @Test
    void cancelMarksInsteadOfDeletingAndRecomputesRevenue() {
        canceladoEm(false);
        when(jdbc.queryForObject(
                contains("SELECT obra_id"),
                eq(String.class),
                eq("rdo-1")
        )).thenReturn("obra-1");
        when(jdbc.update(contains("cancelado_em = CURRENT_TIMESTAMP"), eq("rdo-1")))
                .thenReturn(1);
        RdoResponse response = respostaCanonica("CANCELADA");

        assertThat(service.cancelar("rdo-1")).isSameAs(response);

        verify(guard).requireWritable("obra-1");
        verify(memoryPublisher).registrarRdoCancelado(
                "rdo-1", "obra-1", null, "RDO-014"
        );
        verify(previsao).recalcularAposMudancaRdo(
                "obra-1", LocalDate.of(2026, 8, 1), null
        );
        // Nenhum DELETE: mão de obra, equipamento e medição continuam onde
        // estão, e é por isso que a recuperação é possível.
        verify(jdbc, never()).update(contains("DELETE"), any(Object[].class));
    }

    @Test
    void cancelIsIdempotentAndSkipsTheWorksiteGuard() {
        canceladoEm(true);
        RdoResponse response = respostaCanonica("CANCELADA");
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(guard).requireWritable(anyString());

        assertThat(service.cancelar("rdo-1")).isSameAs(response);

        verify(guard, never()).requireWritable(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(memoryPublisher, never()).registrarRdoCancelado(
                anyString(), anyString(), any(), anyString()
        );
    }

    @Test
    void restoreDerivesTheStatusFromTheSendTimestamp() {
        canceladoEm(true);
        when(jdbc.queryForObject(
                contains("SELECT obra_id"),
                eq(String.class),
                eq("rdo-1")
        )).thenReturn("obra-1");
        when(jdbc.update(contains("cancelado_em = NULL"), eq("rdo-1")))
                .thenReturn(1);
        RdoResponse response = respostaCanonica("ENVIADO");

        assertThat(service.restaurar("rdo-1")).isSameAs(response);

        verify(memoryPublisher).registrarRdoRestaurado(
                "rdo-1", "obra-1", null, "RDO-014", "ENVIADO"
        );
        verify(previsao).recalcularAposMudancaRdo(
                "obra-1", LocalDate.of(2026, 8, 1), null
        );
    }

    @Test
    void archivedWorksiteBlocksCancelBeforeAnyWrite() {
        canceladoEm(false);
        when(jdbc.queryForObject(
                contains("SELECT obra_id"),
                eq(String.class),
                eq("rdo-1")
        )).thenReturn("obra-1");
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(guard).requireWritable("obra-1");

        assertThatThrownBy(() -> service.cancelar("rdo-1"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND)
                );

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
