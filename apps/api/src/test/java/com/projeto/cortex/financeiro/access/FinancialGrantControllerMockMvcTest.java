package com.projeto.cortex.financeiro.access;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.unit.FinancialUnitType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(FinancialGrantController.class)
@AutoConfigureMockMvc(addFilters = false)
class FinancialGrantControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FinancialGrantService service;

    @MockBean
    private CurrentUserService currentUserService;

    @Test
    void alfaCreatesExplicitGrantWithJustification() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(service.grant(
                "obra-1",
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Necessário para conciliação da obra.",
                "alfa-1"
        )).thenReturn(response("ATIVA", "obra-1"));

        mockMvc.perform(post("/api/obras/obra-1/permissoes-financeiras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "colaboradorId":"beta-1",
                                  "permissao":"FINANCEIRO_VISUALIZAR",
                                  "justificativa":"Necessário para conciliação da obra."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.permissao").value("FINANCEIRO_VISUALIZAR"));
    }

    @Test
    void alfaCreatesExplicitAssetUnitGrant() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(service.grantUnit(
                "unit-asset",
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Responsável pelo equipamento adquirido.",
                "alfa-1"
        )).thenReturn(response("ATIVA", null));

        mockMvc.perform(post(
                        "/api/financeiro/unidades/unit-asset/permissoes"
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "colaboradorId":"beta-1",
                                  "permissao":"FINANCEIRO_VISUALIZAR",
                                  "justificativa":"Responsável pelo equipamento adquirido."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unidadeControleId")
                        .value("unit-asset"))
                .andExpect(jsonPath("$.obraId").isEmpty());
    }

    @Test
    void betaCannotManageGrants() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Alfa"))
                .when(currentUserService).requireAlfa();

        mockMvc.perform(post("/api/obras/obra-1/permissoes-financeiras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "colaboradorId":"beta-1",
                                  "permissao":"FINANCEIRO_VISUALIZAR",
                                  "justificativa":"Necessário para conciliação da obra."
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(service, never()).grant(any(), any(), any(), any(), any());
    }

    @Test
    void revocationRequiresAnAuditableJustification() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(service.revoke(
                "obra-1",
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                "Mudança de responsabilidade.",
                "alfa-1"
        )).thenReturn(response("REVOGADA", "obra-1"));

        mockMvc.perform(delete(
                        "/api/obras/obra-1/permissoes-financeiras/beta-1/FINANCEIRO_VISUALIZAR"
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justificativa\":\"Mudança de responsabilidade.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOGADA"));
    }

    private static FinancialGrantResponse response(
            String status,
            String worksiteId
    ) {
        return new FinancialGrantResponse(
                "grant-1",
                worksiteId == null ? "unit-asset" : "unit-worksite",
                worksiteId == null
                        ? FinancialUnitType.ATIVO
                        : FinancialUnitType.OBRA,
                worksiteId,
                "beta-1",
                "Colaborador",
                FinancialPermission.FINANCEIRO_VISUALIZAR,
                status,
                "Necessário para conciliação da obra.",
                LocalDateTime.parse("2026-07-14T10:00:00"),
                "alfa-1",
                "REVOGADA".equals(status)
                        ? LocalDateTime.parse("2026-07-14T11:00:00")
                        : null,
                "REVOGADA".equals(status) ? "alfa-1" : null
        );
    }
}
