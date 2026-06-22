package com.projeto.cortex.intelligence.stavia.knowledge.ontology;

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
import java.util.Set;

@Component
public class OntologyKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final int MAXIMUM_GRAPH_DEPTH = 3;
    private static final int MAXIMUM_RELATIONS = 200;

    private static final Set<StaviaIntent> SUPPORTED_INTENTS =
            Set.of(
                    StaviaIntent.CONSULTAR_ESTADO_ATUAL,
                    StaviaIntent.CONSULTAR_RDO,
                    StaviaIntent.CONSULTAR_PROGRAMACAO,
                    StaviaIntent.CONSULTAR_EQUIPE,
                    StaviaIntent.CONSULTAR_ATIVO,
                    StaviaIntent.RESUMIR_OBRA
            );

    private final OntologyReader ontologyReader;

    public OntologyKnowledgeSource(
            OntologyReader ontologyReader
    ) {
        this.ontologyReader = ontologyReader;
    }

    @Override
    public String sourceName() {
        return "ontologia-operacional";
    }

    @Override
    public String sourceVersion() {
        return StaviaVersions.ONTOLOGY_SOURCE;
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

        return SUPPORTED_INTENTS.contains(
                request.intent()
        );
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        return ontologyReader.findByWorksiteGraph(
                        request.worksiteId(),
                        MAXIMUM_GRAPH_DEPTH,
                        MAXIMUM_RELATIONS
                )
                .stream()
                .map(relation ->
                        toEvidence(
                                request.worksiteId(),
                                relation
                        )
                )
                .toList();
    }

    private StaviaEvidence toEvidence(
            String worksiteId,
            OntologyRelation relation
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put("obraId", worksiteId);

        attributes.put(
                "originType",
                relation.originType()
        );
        attributes.put(
                "originId",
                relation.originId()
        );
        attributes.put(
                "originExternalCode",
                relation.originExternalCode()
        );
        attributes.put(
                "originName",
                relation.originName()
        );
        attributes.put(
                "originStatus",
                relation.originStatus()
        );

        attributes.put(
                "relationType",
                relation.relationType()
        );

        attributes.put(
                "destinationType",
                relation.destinationType()
        );
        attributes.put(
                "destinationId",
                relation.destinationId()
        );
        attributes.put(
                "destinationExternalCode",
                relation.destinationExternalCode()
        );
        attributes.put(
                "destinationName",
                relation.destinationName()
        );
        attributes.put(
                "destinationStatus",
                relation.destinationStatus()
        );

        attributes.put(
                "source",
                relation.source()
        );
        attributes.put(
                "observations",
                relation.observations()
        );

        return new StaviaEvidence(
                StaviaEvidenceTypes.RELACAO_ONTOLOGICA,
                relation.relationId(),
                buildSummary(relation),
                relation.updatedAt(),
                true,
                attributes
        );
    }

    private String buildSummary(
            OntologyRelation relation
    ) {
        return displayName(
                relation.originType(),
                relation.originExternalCode(),
                relation.originName(),
                relation.originId()
        )
                + " "
                + readableRelation(
                        relation.relationType()
                )
                + " "
                + displayName(
                        relation.destinationType(),
                        relation.destinationExternalCode(),
                        relation.destinationName(),
                        relation.destinationId()
                )
                + ".";
    }

    private String displayName(
            String type,
            String externalCode,
            String name,
            String id
    ) {
        if (name != null && !name.isBlank()) {
            return type + " " + name;
        }

        if (
                externalCode != null
                && !externalCode.isBlank()
        ) {
            return type + " " + externalCode;
        }

        return type + " " + id;
    }

    private String readableRelation(
            String relationType
    ) {
        return switch (relationType) {
            case "PERTENCE_A" ->
                    "pertence a";

            case "GERADO_A_PARTIR_DE" ->
                    "foi gerado a partir de";

            case "ALOCADO_EM" ->
                    "está alocado em";

            case "RESPONSAVEL_POR" ->
                    "é responsável por";

            default ->
                    relationType
                            .toLowerCase()
                            .replace('_', ' ');
        };
    }
}
