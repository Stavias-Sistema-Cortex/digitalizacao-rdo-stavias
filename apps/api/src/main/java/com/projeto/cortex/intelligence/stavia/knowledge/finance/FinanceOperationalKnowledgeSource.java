package com.projeto.cortex.intelligence.stavia.knowledge.finance;

import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaBusinessSemanticCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FinanceOperationalKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final String FINANCE_PERMISSION =
            FinancialPermission.FINANCEIRO_VISUALIZAR.name();
    private static final Pattern PURCHASE_REFERENCE = Pattern.compile(
            "(?iu)\\b(?:compra|pedido)\\s+([\\p{L}0-9_-]*\\d[\\p{L}0-9_-]*)"
    );
    private static final Set<StaviaIntent> SUPPORTED = Set.of(
            StaviaIntent.CONSULTAR_NOTAS_FISCAIS_VENCIDAS,
            StaviaIntent.CONSULTAR_HISTORICO_COMPRA,
            StaviaIntent.CONSULTAR_TOTAL_COMPRADO,
            StaviaIntent.CONSULTAR_FORNECEDORES_COBRANCA_PENDENTE
    );

    private final FinanceOperationalKnowledgeReader reader;
    private final Clock clock;

    @Autowired
    public FinanceOperationalKnowledgeSource(
            FinanceOperationalKnowledgeReader reader
    ) {
        this(reader, Clock.system(BUSINESS_ZONE));
    }

    FinanceOperationalKnowledgeSource(
            FinanceOperationalKnowledgeReader reader,
            Clock clock
    ) {
        this.reader = reader;
        this.clock = clock == null ? Clock.system(BUSINESS_ZONE) : clock;
    }

    @Override
    public String sourceName() {
        return StaviaBusinessSemanticCatalog.FINANCE_SOURCE;
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-FINANCE-OPERATIONAL-1.0.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(QueryDomain.FINANCEIRO),
                Set.of(
                        StaviaEvidenceTypes.NOTA_FISCAL_VENCIDA,
                        StaviaEvidenceTypes.COMPRA_HISTORICO,
                        StaviaEvidenceTypes.COMPRA_AGREGADO,
                        StaviaEvidenceTypes.FORNECEDOR_COBRANCA_PENDENTE
                ),
                Set.of(
                        "notaFiscalId", "valorAberto", "vencimentoEm",
                        "compraId", "criadoPorId", "statusNovo",
                        "totalComprado", "quantidadeCompras",
                        "fornecedorId", "quantidadeCobrancasPendentes",
                        "freshnessAt"
                ),
                Set.of(),
                Set.of(
                        QueryOperation.LIST_OBJECTS,
                        QueryOperation.AGGREGATE
                ),
                Set.of(
                        StaviaEngine.REQUIRED_PERMISSION,
                        FINANCE_PERMISSION
                ),
                "Leitura transacional sob demanda",
                10,
                100
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        return request != null
                && request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)
                && request.permissions().contains(FINANCE_PERMISSION)
                && SUPPORTED.contains(request.intent());
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        if (!supports(request)) {
            return List.of();
        }

        List<StaviaEvidence> evidences = switch (request.intent()) {
            case CONSULTAR_NOTAS_FISCAIS_VENCIDAS -> reader.overdueInvoices(
                            request.worksiteId(),
                            LocalDate.now(clock)
                    ).stream()
                    .map(row -> overdueInvoiceEvidence(request.worksiteId(), row))
                    .toList();
            case CONSULTAR_HISTORICO_COMPRA -> reader.purchaseHistory(
                            request.worksiteId(),
                            purchaseReference(request.question().text())
                    ).stream()
                    .map(row -> purchaseHistoryEvidence(request.worksiteId(), row))
                    .toList();
            case CONSULTAR_TOTAL_COMPRADO -> purchasedTotalEvidence(request);
            case CONSULTAR_FORNECEDORES_COBRANCA_PENDENTE ->
                    reader.suppliersWithPendingCharges(request.worksiteId())
                            .stream()
                            .map(row -> pendingSupplierEvidence(
                                    request.worksiteId(),
                                    row
                            ))
                            .toList();
            default -> List.of();
        };

        return evidences.isEmpty()
                ? List.of(unavailableEvidence(request))
                : evidences;
    }

    private List<StaviaEvidence> purchasedTotalEvidence(
            StaviaKnowledgeRequest request
    ) {
        YearMonth month = YearMonth.now(clock);
        LocalDate start = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        return reader.purchasedTotal(request.worksiteId(), start, end)
                .stream()
                .map(row -> {
                    Map<String, Object> attributes = base(
                            request.worksiteId(),
                            row.freshnessAt()
                    );
                    put(attributes, "moeda", row.currency());
                    put(attributes, "totalComprado", row.total());
                    attributes.put("quantidadeCompras", row.purchaseCount());
                    attributes.put("periodoInicio", start.toString());
                    attributes.put("periodoFimExclusivo", end.toString());
                    return new StaviaEvidence(
                            StaviaEvidenceTypes.COMPRA_AGREGADO,
                            "COMPRA_AGREGADO:" + request.worksiteId()
                                    + ":" + month + ":" + row.currency(),
                            "No mês " + month + ", a obra possui "
                                    + row.purchaseCount() + " compra(s), totalizando "
                                    + money(row.total(), row.currency()) + ".",
                            row.freshnessAt(),
                            true,
                            attributes
                    );
                })
                .toList();
    }

    private StaviaEvidence overdueInvoiceEvidence(
            String worksiteId,
            FinanceOperationalKnowledgeReader.OverdueInvoice row
    ) {
        Map<String, Object> attributes = base(worksiteId, row.freshnessAt());
        put(attributes, "notaFiscalId", row.invoiceId());
        put(attributes, "fornecedorId", row.supplierId());
        put(attributes, "fornecedorNome", row.supplierName());
        put(attributes, "numero", row.number());
        put(attributes, "serie", row.series());
        put(attributes, "moeda", row.currency());
        put(attributes, "valorAberto", row.openAmount());
        if (row.dueDate() != null) {
            attributes.put("vencimentoEm", row.dueDate().toString());
        }
        return new StaviaEvidence(
                StaviaEvidenceTypes.NOTA_FISCAL_VENCIDA,
                "NOTA_FISCAL:" + row.invoiceId(),
                "A nota fiscal " + row.number() + " do fornecedor "
                        + row.supplierName() + " venceu em " + row.dueDate()
                        + " e possui " + money(row.openAmount(), row.currency())
                        + " em aberto.",
                row.freshnessAt(),
                true,
                attributes
        );
    }

    private StaviaEvidence purchaseHistoryEvidence(
            String worksiteId,
            FinanceOperationalKnowledgeReader.PurchaseHistory row
    ) {
        Map<String, Object> attributes = base(worksiteId, row.freshnessAt());
        put(attributes, "compraId", row.purchaseId());
        put(attributes, "numeroPedido", row.orderNumber());
        put(attributes, "descricao", row.description());
        put(attributes, "criadoPorId", row.createdById());
        put(attributes, "criadoPorNome", row.createdByName());
        put(attributes, "criadoEm", row.createdAt());
        put(attributes, "historicoId", row.historyId());
        put(attributes, "statusAnterior", row.previousStatus());
        put(attributes, "statusNovo", row.newStatus());
        put(attributes, "atorId", row.actorId());
        put(attributes, "atorNome", row.actorName());
        put(attributes, "origem", row.origin());
        put(attributes, "resultado", row.result());
        put(attributes, "ocorridoEm", row.occurredAt());

        String event = row.historyId() == null
                ? "CRIACAO"
                : row.historyId();
        String summary = row.historyId() == null
                ? "A compra " + reference(row.purchaseId(), row.orderNumber())
                        + " foi criada por " + row.createdByName() + "."
                : "A compra " + reference(row.purchaseId(), row.orderNumber())
                        + " passou para " + row.newStatus() + " por "
                        + row.actorName() + ".";
        return new StaviaEvidence(
                StaviaEvidenceTypes.COMPRA_HISTORICO,
                "COMPRA:" + row.purchaseId() + ":HISTORICO:" + event,
                summary,
                row.freshnessAt(),
                true,
                attributes
        );
    }

    private StaviaEvidence pendingSupplierEvidence(
            String worksiteId,
            FinanceOperationalKnowledgeReader.PendingSupplierCharge row
    ) {
        Map<String, Object> attributes = base(worksiteId, row.freshnessAt());
        put(attributes, "fornecedorId", row.supplierId());
        put(attributes, "fornecedorNome", row.supplierName());
        attributes.put("quantidadeCobrancasPendentes", row.pendingCount());
        put(attributes, "primeiraOcorrenciaPrevistaEm", row.firstExpectedAt());
        return new StaviaEvidence(
                StaviaEvidenceTypes.FORNECEDOR_COBRANCA_PENDENTE,
                "FORNECEDOR:" + row.supplierId() + ":COBRANCAS_PENDENTES",
                "O fornecedor " + row.supplierName() + " possui "
                        + row.pendingCount() + " cobrança(s) ainda pendente(s).",
                row.freshnessAt(),
                true,
                attributes
        );
    }

    private StaviaEvidence unavailableEvidence(StaviaKnowledgeRequest request) {
        Map<String, Object> attributes = base(request.worksiteId(), null);
        attributes.put("available", false);
        attributes.put("reason", "SEM_EVIDENCIA_NO_SERVIDOR");
        return new StaviaEvidence(
                evidenceType(request.intent()),
                "FINANCEIRO:SEM_EVIDENCIA:" + request.intent()
                        + ":" + request.worksiteId(),
                "Não há evidência financeira persistida no servidor para esta consulta.",
                null,
                false,
                attributes
        );
    }

    private String evidenceType(StaviaIntent intent) {
        return switch (intent) {
            case CONSULTAR_NOTAS_FISCAIS_VENCIDAS ->
                    StaviaEvidenceTypes.NOTA_FISCAL_VENCIDA;
            case CONSULTAR_HISTORICO_COMPRA ->
                    StaviaEvidenceTypes.COMPRA_HISTORICO;
            case CONSULTAR_TOTAL_COMPRADO ->
                    StaviaEvidenceTypes.COMPRA_AGREGADO;
            case CONSULTAR_FORNECEDORES_COBRANCA_PENDENTE ->
                    StaviaEvidenceTypes.FORNECEDOR_COBRANCA_PENDENTE;
            default -> StaviaEvidenceTypes.PREVISAO_FINANCEIRA;
        };
    }

    private Map<String, Object> base(String worksiteId, Instant freshnessAt) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", worksiteId);
        if (freshnessAt != null) {
            attributes.put("freshnessAt", freshnessAt.toString());
        }
        return attributes;
    }

    private String purchaseReference(String question) {
        if (question == null) {
            return null;
        }
        Matcher matcher = PURCHASE_REFERENCE.matcher(question);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String reference(String purchaseId, String orderNumber) {
        return orderNumber == null || orderNumber.isBlank()
                ? purchaseId
                : orderNumber;
    }

    private String money(BigDecimal amount, String currency) {
        return (currency == null ? "" : currency + " ")
                + (amount == null ? "não informado" : amount.toPlainString());
    }

    private void put(Map<String, Object> attributes, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            attributes.put(key, value);
        }
    }
}
