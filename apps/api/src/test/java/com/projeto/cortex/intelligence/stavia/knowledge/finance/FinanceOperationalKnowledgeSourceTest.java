package com.projeto.cortex.intelligence.stavia.knowledge.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FinanceOperationalKnowledgeSourceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-14T12:00:00Z"),
            ZoneOffset.UTC
    );

    private final FinanceOperationalKnowledgeReader reader =
            mock(FinanceOperationalKnowledgeReader.class);
    private final FinanceOperationalKnowledgeSource source =
            new FinanceOperationalKnowledgeSource(reader, CLOCK);

    @Test
    void requiresFinancialCapabilityBeforeReading() {
        StaviaKnowledgeRequest request = request(
                StaviaIntent.CONSULTAR_NOTAS_FISCAIS_VENCIDAS,
                Set.of(StaviaEngine.REQUIRED_PERMISSION)
        );

        assertThat(source.supports(request)).isFalse();
        assertThat(source.retrieve(request)).isEmpty();
        verifyNoInteractions(reader);
    }

    @Test
    void emitsTraceableTypedInvoiceEvidence() {
        when(reader.overdueInvoices("obra-1", LocalDate.now(CLOCK)))
                .thenReturn(List.of(new FinanceOperationalKnowledgeReader.OverdueInvoice(
                        "nf-1", "fornecedor-1", "Fornecedor Real", "NF-88", "A",
                        "BRL", new BigDecimal("1250.50"),
                        LocalDate.now(CLOCK).minusDays(5), Instant.parse("2026-07-14T12:00:00Z")
                )));

        var evidences = source.retrieve(request(
                StaviaIntent.CONSULTAR_NOTAS_FISCAIS_VENCIDAS,
                financePermissions()
        ));

        assertThat(evidences).singleElement().satisfies(evidence -> {
            assertThat(evidence.type())
                    .isEqualTo(StaviaEvidenceTypes.NOTA_FISCAL_VENCIDA);
            assertThat(evidence.id()).isEqualTo("NOTA_FISCAL:nf-1");
            assertThat(evidence.attributes())
                    .containsEntry("obraId", "obra-1")
                    .containsEntry("notaFiscalId", "nf-1")
                    .doesNotContainKeys("estadoNovoJson", "payloadJson");
        });
    }

    @Test
    void emitsCurrentMonthAggregateFromReader() {
        YearMonth month = YearMonth.now(CLOCK);
        when(reader.purchasedTotal(
                "obra-1", month.atDay(1), month.plusMonths(1).atDay(1)
        )).thenReturn(List.of(
                new FinanceOperationalKnowledgeReader.PurchasedTotal(
                        "BRL", new BigDecimal("9800.00"), 3,
                        Instant.parse("2026-07-14T12:00:00Z")
                )
        ));

        var evidences = source.retrieve(request(
                StaviaIntent.CONSULTAR_TOTAL_COMPRADO,
                financePermissions()
        ));

        assertThat(evidences).singleElement().satisfies(evidence -> {
            assertThat(evidence.id()).startsWith("COMPRA_AGREGADO:obra-1:");
            assertThat(evidence.attributes())
                    .containsEntry("quantidadeCompras", 3)
                    .containsEntry("totalComprado", new BigDecimal("9800.00"));
        });
    }

    private StaviaKnowledgeRequest request(
            StaviaIntent intent,
            Set<String> permissions
    ) {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion("pergunta", "usuario-1", "obra-1"),
                intent,
                "obra-1",
                permissions
        );
    }

    private Set<String> financePermissions() {
        return Set.of(
                StaviaEngine.REQUIRED_PERMISSION,
                FinancialPermission.FINANCEIRO_VISUALIZAR.name()
        );
    }
}
