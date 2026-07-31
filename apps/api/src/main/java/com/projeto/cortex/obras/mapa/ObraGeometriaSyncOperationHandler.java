package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncPushRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adaptador canônico da camada geoespacial para o outbox offline.
 *
 * <p>Desenhar um trecho no mapa e registrar um ponto observado em campo são
 * mutações de usuário como qualquer outra: escritas primeiro no dispositivo e
 * empurradas quando houver rede. O handler apenas traduz o envelope canônico
 * para o contrato já existente de {@link ObraMapaService}, que continua sendo o
 * único lugar que autoriza, valida o GeoJSON, confere a versão base e publica a
 * evidência na memória operacional.</p>
 */
@Component
public class ObraGeometriaSyncOperationHandler implements SyncOperationHandler {

    static final String ENTITY_TYPE = "GEOMETRIA_OBRA";
    private static final String REGISTRAR = "REGISTRAR_GEOMETRIA_OBRA";
    private static final String REGISTRAR_CAMPO = "REGISTRAR_GEOMETRIA_CAMPO";
    private static final String ATUALIZAR = "ATUALIZAR_GEOMETRIA_OBRA";
    private static final String ENCERRAR = "ENCERRAR_GEOMETRIA_OBRA";
    private static final Set<String> OPERATIONS =
            Set.of(REGISTRAR, REGISTRAR_CAMPO, ATUALIZAR, ENCERRAR);
    private static final Set<String> VERSIONED_OPERATIONS = Set.of(ATUALIZAR, ENCERRAR);

    private final ObraMapaService service;
    private final ObjectMapper objectMapper;

    public ObraGeometriaSyncOperationHandler(
            ObraMapaService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public boolean requiresBaseVersion(String operation) {
        return VERSIONED_OPERATIONS.contains(operation);
    }

    @Override
    public AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    ) {
        ObjectNode payload = requirePayload(mutation);
        String obraId = requireText(payload, "obraId");

        ObraGeometriaResponse response;
        String eventType;
        switch (mutation.operacao()) {
            case REGISTRAR -> {
                response = service.criar(obraId, geometryRequest(payload, null));
                eventType = "GEOMETRIA_CRIADA";
            }
            case REGISTRAR_CAMPO -> {
                response = service.registrarCapturaCampo(
                        obraId, geometryRequest(payload, null)
                );
                eventType = "GEOMETRIA_CRIADA";
            }
            case ATUALIZAR -> {
                long baseVersion = requireBaseVersion(mutation);
                response = service.atualizar(
                        obraId,
                        requireFeatureId(mutation, payload),
                        geometryRequest(payload, baseVersion)
                );
                eventType = "GEOMETRIA_ATUALIZADA";
            }
            case ENCERRAR -> {
                long baseVersion = requireBaseVersion(mutation);
                response = service.encerrar(
                        obraId,
                        requireFeatureId(mutation, payload),
                        new ObraGeometriaEndRequest(
                                baseVersion,
                                text(payload, "motivo"),
                                dateTime(payload, "validoAte")
                        )
                );
                eventType = "GEOMETRIA_ENCERRADA";
            }
            default -> throw badRequest("Operação geoespacial não suportada.");
        }

        return new AppliedSyncMutation(
                ENTITY_TYPE,
                response.id(),
                objectMapper.valueToTree(response),
                new AppliedSyncMutation.AuthoritativeEvent(
                        eventType,
                        List.of(new AppliedSyncMutation.AuthoritativeRelatedEntity(
                                "OBRA", obraId
                        ))
                )
        );
    }

    private ObraGeometriaRequest geometryRequest(ObjectNode payload, Long baseVersion) {
        JsonNode geometry = payload.get("geometry");
        if (geometry == null || !geometry.isObject()) {
            throw badRequest("payload.geometry deve conter um GeoJSON.");
        }
        return new ObraGeometriaRequest(
                text(payload, "categoria"),
                text(payload, "objetoTipo"),
                text(payload, "objetoId"),
                geometry,
                properties(payload),
                text(payload, "fonte"),
                dateTime(payload, "validoDesde"),
                baseVersion,
                text(payload, "motivo")
        );
    }

    private Map<String, Object> properties(ObjectNode payload) {
        JsonNode properties = payload.get("properties");
        if (properties == null || properties.isNull()) {
            return Map.of();
        }
        if (!properties.isObject()) {
            throw badRequest("payload.properties deve ser um objeto.");
        }
        return objectMapper.convertValue(properties, new com.fasterxml.jackson.core.type
                .TypeReference<Map<String, Object>>() { });
    }

    private ObjectNode requirePayload(SyncPushRequest.MutacaoCliente mutation) {
        if (mutation == null
                || mutation.payload() == null
                || !mutation.payload().isObject()) {
            throw badRequest("Payload geoespacial inválido.");
        }
        return (ObjectNode) mutation.payload();
    }

    private String requireFeatureId(
            SyncPushRequest.MutacaoCliente mutation,
            ObjectNode payload
    ) {
        String envelopeId = mutation.entityId();
        String payloadId = text(payload, "id");
        if (envelopeId == null || envelopeId.isBlank()) {
            throw badRequest("entityId da geometria é obrigatório.");
        }
        if (payloadId != null && !payloadId.equals(envelopeId)) {
            throw badRequest("payload.id diverge do envelope canônico.");
        }
        return envelopeId;
    }

    private long requireBaseVersion(SyncPushRequest.MutacaoCliente mutation) {
        Long baseVersion = mutation.baseVersion();
        if (baseVersion == null || baseVersion < 0) {
            throw badRequest("baseVersion é obrigatório e não pode ser negativo.");
        }
        return baseVersion;
    }

    private String requireText(ObjectNode payload, String field) {
        String value = text(payload, field);
        if (value == null || value.isBlank()) {
            throw badRequest("payload." + field + " é obrigatório.");
        }
        return value;
    }

    private String text(ObjectNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw badRequest("payload." + field + " deve ser texto.");
        }
        return value.textValue();
    }

    private LocalDateTime dateTime(ObjectNode payload, String field) {
        String value = text(payload, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException error) {
            throw badRequest("payload." + field + " deve ser um instante local ISO-8601.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
