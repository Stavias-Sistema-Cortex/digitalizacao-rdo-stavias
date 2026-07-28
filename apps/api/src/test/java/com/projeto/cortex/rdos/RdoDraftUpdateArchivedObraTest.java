package com.projeto.cortex.rdos;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdoDraftUpdateArchivedObraTest {

    @Test
    void archivedWorksiteBlocksDraftUpdateBeforeProvenanceReadOrWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RdoChangeAuditService audit = mock(RdoChangeAuditService.class);
        ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
        RdoCreateRequest request = mock(RdoCreateRequest.class);
        when(audit.carregar("rdo-1")).thenReturn(
                new RdoChangeAuditService.RdoAuditSnapshot(
                        "rdo-1",
                        "obra-1",
                        null,
                        "RDO-0001",
                        "RASCUNHO",
                        1,
                        Map.of(),
                        Map.of()
                )
        );
        when(request.obraId()).thenReturn("obra-1");
        when(request.dataRdo()).thenReturn(LocalDate.of(2026, 7, 28));
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(guard).requireWritable("obra-1");
        RdoDraftUpdateService service = new RdoDraftUpdateService(
                jdbc,
                mock(RdoQueryService.class),
                mock(RdoAssetEligibilityService.class),
                mock(RdoMemoryPublisher.class),
                audit,
                mock(RdoOperationalDetailService.class),
                mock(RdoAttachmentService.class),
                mock(RdoOperationalEventService.class),
                mock(PrevisaoFinanceiraService.class),
                guard
        );

        assertThatThrownBy(() -> service.atualizarRascunho("rdo-1", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason()).isEqualTo(
                            "Obra não encontrada ou arquivada."
                    );
        });

        verify(guard).requireWritable("obra-1");
        verify(jdbc, never()).update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }
}
