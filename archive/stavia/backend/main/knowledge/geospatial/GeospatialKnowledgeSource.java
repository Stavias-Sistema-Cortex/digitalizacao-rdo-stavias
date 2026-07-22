package com.projeto.cortex.intelligence.stavia.knowledge.geospatial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GeospatialKnowledgeSource implements StaviaKnowledgeSource {

    private final GeospatialKnowledgeReader reader;
    private final ObjectMapper objectMapper;

    public GeospatialKnowledgeSource(
            GeospatialKnowledgeReader reader,
            ObjectMapper objectMapper
    ) {
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceName() {
        return "geometrias-operacionais";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-GEOSPATIAL-SOURCE-0.1.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(), sourceVersion(), Set.of(QueryDomain.OBRA),
                Set.of("OBRA_GEOMETRIA"),
                Set.of("categoria", "tipoGeometria", "objetoRelacionado", "fonte"),
                Set.of("LOCALIZA", "REPRESENTA"),
                Set.of(QueryOperation.LIST_OBJECTS, QueryOperation.READ_ATTRIBUTE),
                Set.of(StaviaEngine.REQUIRED_PERMISSION), "Sob demanda", 20, 100
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        return request != null
                && request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)
                && request.intent() == StaviaIntent.CONSULTAR_OBRA
                && mentionsMap(request.question().text());
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        List<GeospatialKnowledgeRecord> records =
                reader.findCurrentByWorksiteId(request.worksiteId());
        if (records.isEmpty()) {
            return List.of(noEvidence(request.worksiteId()));
        }
        return records.stream().map(this::toEvidence).toList();
    }

    private StaviaEvidence toEvidence(GeospatialKnowledgeRecord record) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", record.worksiteId());
        attributes.put("geometriaId", record.id());
        attributes.put("categoria", record.category());
        attributes.put("tipoGeometria", record.geometryType());
        attributes.put("fonte", record.source());
        attributes.put("encontrado", true);
        attributes.put("sourceMode", "GEOSPATIAL");
        attributes.put("permissaoAplicada", StaviaEngine.REQUIRED_PERMISSION);
        attributes.put("linkInterno", "/obras/" + record.worksiteId() + "?geometria=" + record.id());
        putText(attributes, "objetoTipo", record.objectType());
        putText(attributes, "objetoId", record.objectId());
        JsonNode properties = parse(record.propertiesJson());
        if (properties != null) attributes.put("propriedades", properties);
        if (record.validSince() != null) {
            attributes.put("validoDesde", record.validSince().toString());
        }

        String summary = "Geometria " + record.id()
                + " registra " + record.category()
                + " como " + record.geometryType()
                + " com fonte " + record.source() + ".";
        return new StaviaEvidence(
                StaviaEvidenceTypes.GEOMETRIA,
                record.id(),
                summary,
                record.updatedAt() == null
                        ? null
                        : record.updatedAt().toInstant(ZoneOffset.UTC),
                true,
                attributes
        );
    }

    private StaviaEvidence noEvidence(String worksiteId) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.GEOMETRIA,
                "GEOMETRIA:NENHUMA:" + worksiteId,
                "Nenhuma geometria operacional vigente foi encontrada para a obra.",
                null,
                false,
                Map.of(
                        "obraId", worksiteId,
                        "encontrado", false,
                        "sourceMode", "GEOSPATIAL",
                        "permissaoAplicada", StaviaEngine.REQUIRED_PERMISSION
                )
        );
    }

    private boolean mentionsMap(String question) {
        String normalized = StaviaText.normalize(question);
        return List.of("mapa", "geometria", "geograf", "perimetro",
                        "frente de trabalho", "ponto operacional", "coordenada")
                .stream().anyMatch(normalized::contains);
    }

    private JsonNode parse(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
