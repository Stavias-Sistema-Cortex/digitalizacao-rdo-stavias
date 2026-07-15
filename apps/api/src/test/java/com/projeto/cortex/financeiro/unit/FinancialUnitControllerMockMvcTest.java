package com.projeto.cortex.financeiro.unit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.CreateFinancialUnitRequest;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitFilter;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(FinancialUnitController.class)
@AutoConfigureMockMvc(addFilters = false)
class FinancialUnitControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FinancialUnitService service;

    @MockBean
    private CurrentUserService currentUser;

    @Test
    void listsUnitsWithManualFiltersInsideAuthenticatedScope() throws Exception {
        when(currentUser.requireUserId()).thenReturn("beta-1");
        when(service.list(
                new FinancialUnitFilter(
                        FinancialUnitType.ATIVO,
                        "ATIVA",
                        "escavadeira"
                ),
                "beta-1"
        )).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/financeiro/unidades")
                        .queryParam("tipo", "ATIVO")
                        .queryParam("status", "ATIVA")
                        .queryParam("busca", "escavadeira"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("unit-asset"))
                .andExpect(jsonPath("$[0].tipo").value("ATIVO"));
    }

    @Test
    void alfaCreatesAdministrativeUnitWithOwnIdentity() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");
        when(service.createAdministrative(
                new CreateFinancialUnitRequest("ADM-SEDE", "Administração"),
                "alfa-1"
        )).thenReturn(new FinancialUnitResponse(
                "unit-admin",
                FinancialUnitType.ADMINISTRATIVO,
                null,
                null,
                "ADM-SEDE",
                "Administração",
                "ATIVA",
                0L
        ));

        mockMvc.perform(post("/api/financeiro/unidades/administrativas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codigo":"ADM-SEDE","nome":"Administração"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("ADMINISTRATIVO"));

        verify(currentUser).requireAlfa();
    }

    @Test
    void betaCannotEnsureAssetUnit() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Alfa"))
                .when(currentUser).requireAlfa();

        mockMvc.perform(post(
                        "/api/financeiro/unidades/ativos/asset-1/garantir"
                )).andExpect(status().isForbidden());

        verify(service, never()).ensureAssetUnit(any(), any());
    }

    private FinancialUnitResponse response() {
        return new FinancialUnitResponse(
                "unit-asset",
                FinancialUnitType.ATIVO,
                null,
                "asset-1",
                "ATIVO:asset-1",
                "Escavadeira",
                "ATIVA",
                0L
        );
    }
}
