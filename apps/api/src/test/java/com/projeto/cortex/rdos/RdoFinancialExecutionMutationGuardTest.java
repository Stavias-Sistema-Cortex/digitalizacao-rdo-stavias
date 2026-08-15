package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class RdoFinancialExecutionMutationGuardTest {

    private static final String RDO_ID = "00000000-0000-4000-8000-000000000101";
    private static final String OBRA_ID = "00000000-0000-4000-8000-000000000102";
    private static final String USER_ID = "00000000-0000-4000-8000-000000000103";
    private static final String EXECUTION_ID = "00000000-0000-4000-8000-000000000104";

    @ParameterizedTest
    @ValueSource(strings = {"VALIDADA", "REJEITADA", "CANCELADA"})
    void criacaoNormalRecusaPayloadQueDeclaraStatusFinanceiroTerminal(
            String status
    ) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObraOperabilityGuard operability = mock(ObraOperabilityGuard.class);
        RdoService service = rdoService(jdbc, operability);

        assertThatThrownBy(() -> service.criarRascunho(
                requestWithServiceStatus(status, EXECUTION_ID)
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.FORBIDDEN));

        verify(operability, never()).requireWritable(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void atualizacaoGenericaRecusaRdoQueJaTemDecisaoFinanceira() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0, 1);
        ObraOperabilityGuard operability = mock(ObraOperabilityGuard.class);
        RdoChangeAuditService audit = mock(RdoChangeAuditService.class);
        when(audit.carregar(RDO_ID)).thenReturn(
                new RdoChangeAuditService.RdoAuditSnapshot(
                        RDO_ID,
                        OBRA_ID,
                        null,
                        "RDO-0001",
                        "RASCUNHO",
                        1L,
                        Map.of(),
                        Map.of()
                )
        );
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
                operability
        );

        assertThatThrownBy(() -> service.atualizarRascunho(
                RDO_ID,
                requestWithServiceStatus("REGISTRADA", EXECUTION_ID)
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining(
                        "RDO_EXECUTION_FINANCIAL_DECISION_IMMUTABLE"
                );

        verify(operability, never()).requireWritable(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void atualizacaoGenericaRecusaEvidenciaLegadaMesmoSemDecisao() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(1);

        assertThatThrownBy(() -> RdoFinancialExecutionMutationGuard
                .assertUpdateCanProceed(
                        jdbc,
                        RDO_ID,
                        requestWithServiceStatus("VALIDADA", EXECUTION_ID)
                ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "RDO_EXECUTION_REVENUE_EVIDENCE_IMMUTABLE"
                );
    }

    private RdoService rdoService(
            JdbcTemplate jdbc,
            ObraOperabilityGuard operability
    ) {
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        return new RdoService(
                jdbc,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                currentUser,
                mock(RdoAssetEligibilityService.class),
                mock(RdoMemoryPublisher.class),
                mock(RdoOperationalDetailService.class),
                mock(RdoAttachmentService.class),
                mock(RdoOperationalEventService.class),
                mock(PrevisaoFinanceiraService.class),
                mock(RdoQueryService.class),
                operability
        );
    }

    private RdoCreateRequest requestWithServiceStatus(
            String status,
            String executionId
    ) {
        return new RdoCreateRequest(
                RDO_ID,
                OBRA_ID,
                null,
                null,
                LocalDate.of(2026, 8, 15),
                null,
                1L,
                "mutation-101",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new RdoCreateRequest.ServicoExecutadoItem(
                        executionId,
                        "00000000-0000-4000-8000-000000000105",
                        "00000000-0000-4000-8000-000000000106",
                        "Fresagem",
                        null,
                        null,
                        "M2",
                        null,
                        null,
                        null,
                        null,
                        status,
                        false,
                        false,
                        null
                )),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
