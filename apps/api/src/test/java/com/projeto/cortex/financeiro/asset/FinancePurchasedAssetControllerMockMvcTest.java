package com.projeto.cortex.financeiro.asset;

import static com.projeto.cortex.financeiro.asset.FinancePurchasedAssetDtos.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
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
        value = FinancePurchasedAssetController.class,
        properties = "cortex.cors.allowed-origins=https://legacy-finance.test"
)
@ActiveProfiles("legacy-finance")
@AutoConfigureMockMvc(addFilters = false)
class FinancePurchasedAssetControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FinancePurchasedAssetService service;

    @MockBean
    private CurrentUserService currentUser;

    @Test
    void confirmsIndividualAssetsWithAuthenticatedActor() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");
        when(service.confirm(any(), any(), any(), any()))
                .thenReturn(response());

        mockMvc.perform(put(
                        "/api/financeiro/compras/purchase-1/itens/item-1/ativos"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientMutationId":"asset-mutation-1",
                                  "baseVersion":4,
                                  "ativos":[
                                    {"ativoExistenteId":"asset-1"},
                                    {"novoAtivo":{
                                      "codigoExterno":"EQ-2",
                                      "nome":"Rolo compactador",
                                      "categoria":"Máquinas"
                                    }}
                                  ],
                                  "correlacaoId":"correlation-1",
                                  "dispositivoId":"device-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compraId").value("purchase-1"))
                .andExpect(jsonPath("$.itemId").value("item-1"))
                .andExpect(jsonPath("$.ativos.length()").value(2));
    }

    @Test
    void readsLinksAndHistory() throws Exception {
        when(service.find("purchase-1", "item-1")).thenReturn(response());
        when(service.history("purchase-1", "item-1"))
                .thenReturn(new PurchasedAssetHistoryResponse(
                        "purchase-1", "item-1", List.of()
                ));

        mockMvc.perform(get(
                        "/api/financeiro/compras/purchase-1/itens/item-1/ativos"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.natureza").value("CAPITALIZAVEL"));
        mockMvc.perform(get(
                        "/api/financeiro/compras/purchase-1/itens/item-1/ativos/historico"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("item-1"));
    }

    private PurchasedAssetResponse response() {
        return new PurchasedAssetResponse(
                "purchase-1",
                "item-1",
                "CAPITALIZAVEL",
                new BigDecimal("2.0000"),
                5L,
                List.of(
                        link("link-1", "asset-1", "unit-1", 1),
                        link("link-2", "asset-2", "unit-2", 2)
                )
        );
    }

    private PurchasedAssetLinkResponse link(
            String linkId,
            String assetId,
            String unitId,
            int sequence
    ) {
        return new PurchasedAssetLinkResponse(
                linkId,
                assetId,
                unitId,
                sequence,
                "EQ-" + sequence,
                "Equipamento " + sequence,
                "Máquinas",
                "alfa-1",
                LocalDateTime.parse("2026-07-14T20:00:00")
        );
    }
}
