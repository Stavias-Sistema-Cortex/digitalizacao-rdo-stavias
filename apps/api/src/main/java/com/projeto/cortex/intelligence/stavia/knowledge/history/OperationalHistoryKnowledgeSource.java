package com.projeto.cortex.intelligence.stavia.knowledge.history;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OperationalHistoryKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final int MAXIMUM_GRAPH_DEPTH = 3;
    private static final int MAXIMUM_EVENTS = 200;

    private final OperationalHistoryReader historyReader;

    public OperationalHistoryKnowledgeSource(
            OperationalHistoryReader historyReader
    ) {
        this.historyReader = historyReader;
    }

    @Override
    public String sourceName() {
        return "historico-operacional";
    }

    @Override
    public String sourceVersion() {
        return StaviaVersions.OPERATIONAL_HISTORY_SOURCE;
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(
                        QueryDomain.ONTOLOGIA,
                        QueryDomain.OBRA,
                        QueryDomain.RDO,
                        QueryDomain.PROGRAMACAO,
                        QueryDomain.COLABORADOR,
                        QueryDomain.EQUIPE,
                        QueryDomain.EQUIPAMENTO,
                        QueryDomain.FINANCEIRO,
                        QueryDomain.FREQUENCIA,
                        QueryDomain.BANCO_HORAS
                ),
                Set.of(
                        "OBRA",
                        "RDO",
                        "PROGRAMACAO_OPERACIONAL",
                        "COLABORADOR",
                        "EQUIPAMENTO",
                        "CONTEXTO_OBRA"
                ),
                Set.of(),
                Set.of(),
                Set.of(
                        QueryOperation.TRAVERSE_RELATIONSHIP,
                        QueryOperation.SUMMARIZE,
                        QueryOperation.LIST_OBJECTS,
                        QueryOperation.READ_ATTRIBUTE
                ),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Eventos operacionais locais do Córtex",
                90,
                MAXIMUM_EVENTS
        );
    }

    @Override
    public boolean supports(
            StaviaKnowledgeRequest request
    ) {
        if (request == null) {
            return false;
        }

        if (
                !request.permissions().contains(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        ) {
            return false;
        }

        if (
                request.plan() != null
                        && request.plan().planned()
                        && (
                        request.plan().requiresHistory()
                                || request.plan().requiredSources()
                                        .contains(sourceName())
                )
        ) {
            return true;
        }

        return switch (request.intent()) {
            case CONSULTAR_HISTORICO,
                    RESUMIR_OBRA -> true;

            case CONSULTAR_OBRA,
                    CONSULTAR_ESTADO_ATUAL,
                    CONSULTAR_RDO,
                    CONSULTAR_PROGRAMACAO,
                    CONSULTAR_EQUIPE,
                    CONSULTAR_ATIVO,
                    CONSULTAR_OCORRENCIA,
                    CONSULTAR_PDOR,
                    CONSULTAR_RECEITA,
                    CONSULTAR_MARGEM,
                    CONSULTAR_PREVISAO_FINANCEIRA,
                    CONSULTAR_PRODUCAO,
                    CONSULTAR_RECEITA_EM_RISCO,
                    CONSULTAR_NOTAS_FISCAIS_VENCIDAS,
                    CONSULTAR_HISTORICO_COMPRA,
                    CONSULTAR_TOTAL_COMPRADO,
                    CONSULTAR_FORNECEDORES_COBRANCA_PENDENTE,
                    CONSULTAR_DOCUMENTOS_MENSAGEM_PENDENTES,
                    CONSULTAR_ALOCACAO_COLABORADOR,
                    CONSULTAR_FREQUENCIA,
                    CONSULTAR_BANCO_HORAS,
                    DESCONHECIDA -> false;
        };
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        return historyReader.findByWorksiteGraph(
                        request.worksiteId(),
                        MAXIMUM_GRAPH_DEPTH,
                        MAXIMUM_EVENTS
                )
                .stream()
                .map(event ->
                        toEvidence(
                                request.worksiteId(),
                                event
                        )
                )
                .toList();
    }

    private StaviaEvidence toEvidence(
            String worksiteId,
            OperationalHistoryEvent event
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put(
                "obraId",
                worksiteId
        );

        attributes.put(
                "commitSequence",
                event.commitSequence()
        );

        attributes.put(
                "eventType",
                event.eventType()
        );

        attributes.put(
                "entityType",
                event.entityType()
        );

        attributes.put(
                "entityId",
                event.entityId()
        );

        attributes.put(
                "source",
                event.source()
        );

        attributes.put(
                "payload",
                event.payload()
        );

        return new StaviaEvidence(
                StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                event.eventId(),
                buildSummary(event),
                event.occurredAt(),
                true,
                attributes
        );
    }

    private String buildSummary(
            OperationalHistoryEvent event
    ) {
        Object numeroRdo = event.payload().get("numeroRdo");
        String rdoLabel =
                numeroRdo == null
                        || String.valueOf(numeroRdo).isBlank()
                        ? "RDO"
                        : "RDO " + numeroRdo;

        if ("RDO_CRIADO".equals(event.eventType())) {
            return rdoLabel + " criado.";
        }

        if ("RDO_ENVIADO".equals(event.eventType())) {
            return rdoLabel + " enviado.";
        }

        if ("RDO_EDITADO".equals(event.eventType())) {
            Object alterations =
                    event.payload().get("alteracoes");

            if (alterations instanceof List<?> list
                    && !list.isEmpty()) {
                Object first = list.getFirst();

                if (first instanceof Map<?, ?> map) {
                    Object label = map.get("rotulo");
                    Object before = map.get("valorAnterior");
                    Object after = map.get("valorNovo");

                    if (label != null) {
                        StringBuilder summary =
                                new StringBuilder();

                        summary.append(label)
                                .append(" ")
                                .append(
                                        changedVerb(
                                                String.valueOf(label)
                                        )
                                );

                        if (before != null || after != null) {
                            summary.append(": ")
                                    .append(valueText(before))
                                    .append(" -> ")
                                    .append(valueText(after));
                        }

                        summary.append(".");
                        return summary.toString();
                    }
                }

                return rdoLabel
                        + " editado com "
                        + list.size()
                        + " alteração(ões).";
            }

            return rdoLabel
                    + " editado sem detalhamento dos campos alterados.";
        }

        String base =
                "Evento "
                        + event.eventType()
                        + " registrado para "
                        + event.entityType();

        if (
                numeroRdo != null
                && !String.valueOf(numeroRdo).isBlank()
        ) {
            base += " " + numeroRdo;
        } else {
            base += " " + event.entityId();
        }

        Object status = event.payload().get("status");

        if (
                status != null
                && !String.valueOf(status).isBlank()
        ) {
            base += ", status " + status;
        }

        return base + ".";
    }

    private String valueText(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "não informado";
        }

        return String.valueOf(value);
    }

    private String changedVerb(String label) {
        String normalized =
                normalize(label);

        if (normalized.endsWith("oes")
                || normalized.endsWith("coes")
                || normalized.endsWith("as")) {
            return "alteradas";
        }

        if (normalized.endsWith("ais")
                || normalized.endsWith("os")
                || normalized.endsWith("s")) {
            return "alterados";
        }

        if (normalized.endsWith("a")
                || normalized.endsWith("ao")
                || normalized.endsWith("cao")
                || normalized.endsWith("gem")) {
            return "alterada";
        }

        return "alterado";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return java.text.Normalizer.normalize(
                        value,
                        java.text.Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT)
                .trim();
    }
}
