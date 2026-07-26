package com.projeto.cortex.financeiro.allocation;

import static com.projeto.cortex.financeiro.allocation.FinanceAllocationDtos.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        value = FinanceAllocationController.class,
        properties = "cortex.cors.allowed-origins=https://legacy-finance.test"
)
@ActiveProfiles("legacy-finance")
@AutoConfigureMockMvc(addFilters = false)
class FinanceAllocationControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FinanceAllocationService service;

    @MockBean
    private CurrentUserService currentUser;

    @Test
    void savesAllocationUsingRouteAsCanonicalSource() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");
        when(service.save(any(), any())).thenReturn(response());

        mockMvc.perform(put(
                        "/api/financeiro/rateios/origens/COMPRA/compra-1"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientMutationId":"mutation-1",
                                  "baseVersion":0,
                                  "itens":[
                                    {"unidadeControleId":"unit-1",
                                     "valor":100.0000}
                                  ],
                                  "correlacaoId":"correlation-1",
                                  "dispositivoId":"device-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("rateio-1"))
                .andExpect(jsonPath("$.origemTipo").value("COMPRA"))
                .andExpect(jsonPath("$.origemId").value("compra-1"))
                .andExpect(jsonPath("$.itens[0].percentual")
                        .value(1.000000));

        verify(service).save(
                eq(new SaveAllocationRequest(
                        "mutation-1",
                        0L,
                        AllocationSourceType.COMPRA,
                        "compra-1",
                        List.of(new AllocationItemRequest(
                                "unit-1", null, null,
                                new BigDecimal("100.0000")
                        )),
                        "correlation-1",
                        "device-1"
                )),
                eq(new FinanceAuditContext(
                        "alfa-1",
                        "device-1",
                        "correlation-1",
                        "ONLINE"
                ))
        );
    }

    @Test
    void getsAllocationBySource() throws Exception {
        when(service.findBySource(
                AllocationSourceType.NOTA_FISCAL,
                "invoice-1"
        )).thenReturn(response());

        mockMvc.perform(get(
                        "/api/financeiro/rateios/origens/NOTA_FISCAL/invoice-1"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("rateio-1"));
    }

    @Test
    void getsAllocationHistory() throws Exception {
        when(service.history("rateio-1")).thenReturn(
                new AllocationHistoryResponse("rateio-1", List.of())
        );

        mockMvc.perform(get(
                        "/api/financeiro/rateios/rateio-1/historico"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateioId").value("rateio-1"));
    }

    @Test
    void listsAllocationsInsideTheAuthenticatedUnitScope() throws Exception {
        when(currentUser.requireUserId()).thenReturn("beta-1");
        when(service.list("unit-1", "beta-1"))
                .thenReturn(List.of(response()));

        mockMvc.perform(get("/api/financeiro/rateios")
                        .queryParam("unidadeId", "unit-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("rateio-1"))
                .andExpect(jsonPath("$[0].itens[0].unidadeControleId")
                        .value("unit-1"));

        verify(service).list("unit-1", "beta-1");
    }

    private AllocationResponse response() {
        return new AllocationResponse(
                "rateio-1",
                AllocationSourceType.COMPRA,
                "compra-1",
                "BRL",
                new BigDecimal("100.0000"),
                "ATIVO",
                List.of(new AllocationItemResponse(
                        "item-1",
                        "unit-1",
                        null,
                        null,
                        new BigDecimal("100.0000"),
                        new BigDecimal("1.000000"),
                        0
                )),
                "alfa-1",
                LocalDateTime.parse("2026-07-14T20:00:00"),
                "alfa-1",
                LocalDateTime.parse("2026-07-14T20:00:00"),
                0L
        );
    }
}
