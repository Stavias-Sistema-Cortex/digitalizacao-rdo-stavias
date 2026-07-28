package com.projeto.cortex.rdos;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
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

class RdoWorkflowServiceArchivedObraTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RdoQueryService queryService = mock(RdoQueryService.class);
    private final ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
    private final RdoWorkflowService service = new RdoWorkflowService(
            jdbc,
            queryService,
            mock(RdoMemoryPublisher.class),
            mock(PrevisaoFinanceiraService.class),
            guard
    );

    @Test
    void alreadySentReplayReturnsBeforeArchivedWorksiteGuard() {
        RdoResponse response = mock(RdoResponse.class);
        when(jdbc.queryForObject(
                contains("SELECT status"),
                eq(String.class),
                eq("rdo-1")
        )).thenReturn("ENVIADO");
        when(queryService.buscarPorId("rdo-1")).thenReturn(response);
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(guard).requireWritable("obra-1");

        assertThat(service.enviar("rdo-1")).isSameAs(response);

        verify(guard, never()).requireWritable(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void archivedWorksiteBlocksFirstSendBeforeStatusWrite() {
        when(jdbc.queryForObject(
                contains("SELECT status"),
                eq(String.class),
                eq("rdo-1")
        )).thenReturn("RASCUNHO");
        when(jdbc.queryForObject(
                contains("SELECT obra_id"),
                eq(String.class),
                eq("rdo-1")
        )).thenReturn("obra-1");
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(guard).requireWritable("obra-1");

        assertThatThrownBy(() -> service.enviar("rdo-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason()).isEqualTo(
                            "Obra não encontrada ou arquivada."
                    );
                });

        verify(guard).requireWritable("obra-1");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(queryService, never()).buscarPorId(anyString());
    }
}
