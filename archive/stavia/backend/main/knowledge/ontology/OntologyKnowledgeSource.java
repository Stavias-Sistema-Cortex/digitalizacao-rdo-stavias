package com.projeto.cortex.intelligence.stavia.knowledge.ontology;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OntologyKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final int MAXIMUM_GRAPH_DEPTH = 3;
    private static final int MAXIMUM_RELATIONS = 200;
    private static final int MAXIMUM_OBJECTS = 200;
    private static final int MAXIMUM_ATTRIBUTES = 400;

    private static final Set<StaviaIntent> SUPPORTED_INTENTS =
            Set.of(
                    StaviaIntent.CONSULTAR_OBRA,
                    StaviaIntent.CONSULTAR_ESTADO_ATUAL,
                    StaviaIntent.CONSULTAR_HISTORICO,
                    StaviaIntent.CONSULTAR_RDO,
                    StaviaIntent.CONSULTAR_PROGRAMACAO,
                    StaviaIntent.CONSULTAR_EQUIPE,
                    StaviaIntent.CONSULTAR_ATIVO,
                    StaviaIntent.CONSULTAR_OCORRENCIA,
                    StaviaIntent.CONSULTAR_PDOR,
                    StaviaIntent.CONSULTAR_RECEITA,
                    StaviaIntent.CONSULTAR_MARGEM,
                    StaviaIntent.CONSULTAR_PREVISAO_FINANCEIRA,
                    StaviaIntent.CONSULTAR_PRODUCAO,
                    StaviaIntent.CONSULTAR_RECEITA_EM_RISCO,
                    StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR,
                    StaviaIntent.CONSULTAR_FREQUENCIA,
                    StaviaIntent.CONSULTAR_BANCO_HORAS,
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
                        "ATIVO",
                        "EQUIPAMENTO",
                        "RDO_MAO_OBRA",
                        "RDO_EQUIPAMENTO",
                        "MATERIAL_RDO",
                        "CONTROLE_GEOMETRICO",
                        "RDO_FOTO",
                        "SERVICO",
                        "EXECUCAO_SERVICO_RDO",
                        "ALOCACAO_COLABORADOR",
                        "ITEM_CONTRATUAL",
                        "PREVISAO_FINANCEIRA",
                        "IMPORTACAO_LEGADA",
                        "CONTEXTO_OBRA"
                ),
                Set.of(
                        "objectId",
                        "entityType",
                        "entityId",
                        "fieldName",
                        "fieldValue",
                        "source",
                        "canonicalKey",
                        "legacySource"
                ),
                Set.of(
                        "PERTENCE_A",
                        "GERADO_A_PARTIR_DE",
                        "ALOCADO_EM",
                        "RESPONSAVEL_POR",
                        "CONTEXTUALIZA",
                        "DOCUMENTA",
                        "REGISTRA_MAO_DE_OBRA",
                        "REFERENCIA_COLABORADOR",
                        "USA_EQUIPAMENTO",
                        "REFERENCIA_ATIVO",
                        "CONSOME_MATERIAL",
                        "POSSUI_CONTROLE_GEOMETRICO",
                        "POSSUI_FOTO",
                        "EXECUTA",
                        "EXECUTA_SERVICO",
                        "REGISTRA_SERVICO",
                        "TEM_SERVICO_EXECUTADO",
                        "ATUOU_EM_SERVICO",
                        "RESPONSAVEL_PELO_SERVICO"
                ),
                Set.of(
                        QueryOperation.TRAVERSE_RELATIONSHIP,
                        QueryOperation.SUMMARIZE,
                        QueryOperation.LIST_OBJECTS,
                        QueryOperation.READ_ATTRIBUTE
                ),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Grafo operacional local do Córtex",
                80,
                MAXIMUM_RELATIONS
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

        return SUPPORTED_INTENTS.contains(
                request.intent()
        );
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        List<StaviaEvidence> evidences = new ArrayList<>();

        ontologyReader.findObjectsByWorksiteGraph(
                        request.worksiteId(),
                        MAXIMUM_GRAPH_DEPTH,
                        MAXIMUM_OBJECTS
                )
                .stream()
                .map(this::toEvidence)
                .forEach(evidences::add);

        ontologyReader.findAttributesByWorksiteGraph(
                        request.worksiteId(),
                        MAXIMUM_GRAPH_DEPTH,
                        MAXIMUM_ATTRIBUTES
                )
                .stream()
                .map(attribute ->
                        toEvidence(
                                request.worksiteId(),
                                attribute
                        )
                )
                .forEach(evidences::add);

        ontologyReader.findByWorksiteGraph(
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
                .forEach(evidences::add);

        return List.copyOf(evidences);
    }

    private StaviaEvidence toEvidence(
            OntologyObject object
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put("objectId", object.objectId());
        attributes.put("entityType", object.entityType());
        attributes.put("entityTable", object.entityTable());
        attributes.put("entityId", object.entityId());
        attributes.put("externalCode", object.externalCode());
        attributes.put("canonicalKey", object.canonicalKey());
        attributes.put("name", object.name());
        attributes.put("status", object.status());
        attributes.put("source", object.source());
        attributes.put("createdAt", object.createdAt());
        attributes.put("lastSeenAt", object.lastSeenAt());

        return new StaviaEvidence(
                StaviaEvidenceTypes.OBJETO_ONTOLOGICO,
                object.objectId(),
                buildSummary(object),
                object.updatedAt(),
                true,
                attributes
        );
    }

    private StaviaEvidence toEvidence(
            String worksiteId,
            OntologyAttribute attribute
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put("obraId", worksiteId);
        attributes.put("objectId", attribute.objectId());
        attributes.put("entityType", attribute.entityType());
        attributes.put("entityId", attribute.entityId());
        attributes.put("fieldName", attribute.fieldName());
        attributes.put("fieldValue", attribute.fieldValue());
        attributes.put("fieldType", attribute.fieldType());
        attributes.put("source", attribute.source());
        attributes.put("confidence", attribute.confidence());
        attributes.put("validAt", attribute.validAt());

        return new StaviaEvidence(
                StaviaEvidenceTypes.ATRIBUTO_ONTOLOGICO,
                attribute.evidenceId(),
                buildSummary(attribute),
                attribute.updatedAt(),
                true,
                attributes
        );
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

    private String buildSummary(
            OntologyObject object
    ) {
        return "Objeto "
                + displayName(
                        object.entityType(),
                        object.externalCode(),
                        object.name(),
                        object.entityId()
                )
                + " registrado na ontologia"
                + statusSuffix(object.status())
                + ".";
    }

    private String buildSummary(
            OntologyAttribute attribute
    ) {
        return displayName(
                attribute.entityType(),
                attribute.objectExternalCode(),
                attribute.objectName(),
                attribute.entityId()
        )
                + " tem "
                + attribute.fieldName()
                + " = "
                + attribute.fieldValue()
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

            case "REGISTRA_MAO_DE_OBRA" ->
                    "registra mão de obra";

            case "REFERENCIA_COLABORADOR" ->
                    "referencia colaborador";

            case "USA_EQUIPAMENTO" ->
                    "usa equipamento";

            case "REFERENCIA_ATIVO" ->
                    "referencia ativo";

            case "CONSOME_MATERIAL" ->
                    "consome material";

            case "POSSUI_CONTROLE_GEOMETRICO" ->
                    "possui controle geométrico";

            case "EXECUTA", "EXECUTA_SERVICO" ->
                    "executa serviço";

            case "REGISTRA_SERVICO" ->
                    "registra serviço";

            case "TEM_SERVICO_EXECUTADO" ->
                    "tem serviço executado";

            case "ATUOU_EM_SERVICO" ->
                    "atuou em serviço";

            case "RESPONSAVEL_PELO_SERVICO" ->
                    "é responsável pelo serviço";

            default ->
                    relationType
                            .toLowerCase()
                            .replace('_', ' ');
        };
    }

    private String statusSuffix(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }

        return " com status " + status;
    }
}
