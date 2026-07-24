package com.projeto.cortex.financeiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RastreioReceitaResponseContractTest {

    private static final String USER_ID =
            "00000000-0000-4000-8000-000000000201";
    private static final String OBRA_ID =
            "00000000-0000-4000-8000-abcdef000301";

    @Test
    void publicContractExposesMeasuredRevenueWithoutCostMarginOrEstimate() {
        assertThat(componentNames(RastreioReceitaResponse.class))
                .contains("totalrevenue", "evidencecount", "rows")
                .noneMatch(this::isLegacyFinancialField);
        assertThat(componentNames(
                RastreioReceitaResponse.RevenueEvidenceRow.class
        )).contains("revenue", "unitprice")
                .noneMatch(this::isLegacyFinancialField);
        assertThat(componentNames(RastreioReceitaEvidenceResponse.class))
                .noneMatch(this::isLegacyFinancialField);
    }

    @Test
    void httpContractSerializesRevenueDecimalsAsExactCanonicalStrings()
            throws Exception {
        RastreioReceitaService service = mock(RastreioReceitaService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(USER_ID);
        when(access.allowedObraIds(
                USER_ID, FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Set.of(OBRA_ID));
        when(service.buscar(Set.of(OBRA_ID), null, null, null))
                .thenReturn(precisionResponse());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RastreioReceitaController(service, access, currentUser)
        ).build();

        String body = mockMvc.perform(get("/api/financeiro/rastreio-receita"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = new ObjectMapper().findAndRegisterModules()
                .readTree(body);

        assertTextDecimal(
                json.path("totalRevenue"), "9007199254740993.99"
        );
        JsonNode row = json.path("rows").path(0);
        assertTextDecimal(row.path("quantity"), "9007199254740993.125");
        assertTextDecimal(row.path("unitPrice"), "9007199254740993.9900");
        assertTextDecimal(row.path("revenue"), "9007199254740993.99");
    }

    private RastreioReceitaResponse precisionResponse() {
        return new RastreioReceitaResponse(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                new BigDecimal("9007199254740993.99"),
                1,
                List.of(new RastreioReceitaResponse.RevenueEvidenceRow(
                        OBRA_ID,
                        "Obra precisão",
                        "00000000-0000-4000-8000-000000000401",
                        "RDO-001",
                        "00000000-0000-4000-8000-000000000402",
                        LocalDate.of(2026, 7, 23),
                        "00000000-0000-4000-8000-000000000403",
                        "SERV-001",
                        "Serviço medido",
                        "00000000-0000-4000-8000-000000000404",
                        7,
                        new BigDecimal("9007199254740993.125"),
                        "m³",
                        new BigDecimal("9007199254740993.9900"),
                        "BRL",
                        new BigDecimal("9007199254740993.99"),
                        "ACCEPTED_EXACT",
                        "00000000-0000-4000-8000-000000000405",
                        "00000000-0000-4000-8000-000000000406",
                        42L,
                        Instant.parse("2026-07-23T12:00:00Z")
                ))
        );
    }

    private void assertTextDecimal(JsonNode value, String expected) {
        assertThat(value.isTextual()).isTrue();
        assertThat(value.textValue()).isEqualTo(expected);
    }

    private java.util.List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    private boolean isLegacyFinancialField(String name) {
        return name.contains("cost")
                || name.contains("custo")
                || name.contains("margin")
                || name.contains("margem")
                || name.contains("estimate")
                || name.contains("estimativa");
    }
}
