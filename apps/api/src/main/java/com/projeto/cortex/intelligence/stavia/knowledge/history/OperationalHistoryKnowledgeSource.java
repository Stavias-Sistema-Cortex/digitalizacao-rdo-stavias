package com.projeto.cortex.intelligence.stavia.knowledge.history;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        return switch (request.intent()) {
            case CONSULTAR_ESTADO_ATUAL,
                    CONSULTAR_HISTORICO,
                    CONSULTAR_RDO,
                    CONSULTAR_PROGRAMACAO,
                    RESUMIR_OBRA -> true;

            case CONSULTAR_OBRA,
                    CONSULTAR_EQUIPE,
                    CONSULTAR_ATIVO,
                    CONSULTAR_OCORRENCIA,
                    CONSULTAR_PDOC,
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
        String base =
                "Evento "
                        + event.eventType()
                        + " registrado para "
                        + event.entityType()
                        + " "
                        + event.entityId();

        Object status = event.payload().get("status");
        Object numeroRdo = event.payload().get("numeroRdo");

        if (
                numeroRdo != null
                && !String.valueOf(numeroRdo).isBlank()
        ) {
            base += ", RDO " + numeroRdo;
        }

        if (
                status != null
                && !String.valueOf(status).isBlank()
        ) {
            base += ", status " + status;
        }

        return base + ".";
    }
}
