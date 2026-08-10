package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * O RDO entregue continua com conserto; o cancelado, não.
 *
 * <p>Antes só RASCUNHO podia ser editado, e um erro percebido depois do envio
 * não tinha saída em lugar nenhum — nem no aparelho, nem aqui. O que muda é a
 * porta, não a verdade do status: editar reabre o RDO, e ele volta a precisar
 * de envio.
 */
class RdoEnviadoContinuaEditavelTest {

    private RdoDraftUpdateService servico(
            JdbcTemplate jdbc,
            RdoChangeAuditService audit,
            ObraOperabilityGuard guard
    ) {
        return new RdoDraftUpdateService(
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
    }

    private RdoChangeAuditService auditoriaCom(String status) {
        RdoChangeAuditService audit = mock(RdoChangeAuditService.class);
        when(audit.carregar("rdo-1")).thenReturn(
                new RdoChangeAuditService.RdoAuditSnapshot(
                        "rdo-1",
                        "obra-1",
                        null,
                        "RDO-0001",
                        status,
                        1,
                        Map.of(),
                        Map.of()
                )
        );
        return audit;
    }

    private RdoCreateRequest pedido() {
        RdoCreateRequest request = mock(RdoCreateRequest.class);
        when(request.obraId()).thenReturn("obra-1");
        when(request.dataRdo()).thenReturn(LocalDate.of(2026, 7, 28));
        return request;
    }

    @Test
    void rdoEnviadoPassaDaPortaEChegaAEscrita() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
        RdoDraftUpdateService service =
                servico(jdbc, auditoriaCom("ENVIADO"), guard);

        /*
         * O que se prende aqui é a porta: um RDO enviado não é mais recusado
         * pelo status. O caminho segue e falha adiante, nas dependências que
         * este teste não monta — e é justamente por passar da porta que ele
         * chega lá.
         */
        assertThatThrownBy(() -> service.atualizarRascunho("rdo-1", pedido()))
                .isNotInstanceOf(ResponseStatusException.class);

        verify(guard).requireWritable("obra-1");
    }

    @Test
    void rdoCanceladoContinuaFechado() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObraOperabilityGuard guard = mock(ObraOperabilityGuard.class);
        RdoDraftUpdateService service =
                servico(jdbc, auditoriaCom("CANCELADA"), guard);

        assertThatThrownBy(() -> service.atualizarRascunho("rdo-1", pedido()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cancelado");

        verify(guard, never()).requireWritable(anyString());
        verify(jdbc, never()).update(anyString(), (Object[]) any());
    }

    private static Object[] any() {
        return org.mockito.ArgumentMatchers.any(Object[].class);
    }
}
