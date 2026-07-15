package com.projeto.cortex.obras.mapa;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class ObraGeometriaMemoryPublisher {

    private static final String SOURCE = "GESTAO_MAPA";
    private final CortexOperationalMemoryService memoryService;

    ObraGeometriaMemoryPublisher(CortexOperationalMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    void criada(ObraGeometriaResponse feature, String obraId, String actorId) {
        registerObjectAndRelations(feature, obraId);
        publish("GEOMETRIA_CRIADA", feature, obraId, actorId, "CRIAR", Map.of(),
                state(feature), null, null);
    }

    void atualizada(
            ObraGeometriaResponse before,
            ObraGeometriaResponse after,
            String obraId,
            String actorId,
            long baseVersion,
            String reason
    ) {
        if (!java.util.Objects.equals(before.objetoTipo(), after.objetoTipo())
                || !java.util.Objects.equals(before.objetoId(), after.objetoId())) {
            closeRepresentedObjectRelation(before);
        }
        registerObjectAndRelations(after, obraId);
        publish("GEOMETRIA_ATUALIZADA", after, obraId, actorId, "ATUALIZAR",
                state(before), state(after), baseVersion, reason);
    }

    void encerrada(
            ObraGeometriaResponse before,
            ObraGeometriaResponse after,
            String obraId,
            String actorId,
            long baseVersion,
            String reason
    ) {
        memoryService.encerrarRelacaoAtiva(
                "GEOMETRIA_OPERACIONAL", after.id(), "OBRA", obraId, "GEOREFERENCIA"
        );
        closeRepresentedObjectRelation(after);
        memoryService.registrarObjeto(
                "GEOMETRIA_OPERACIONAL", after.id(), after.id(),
                after.categoria(), after.status(), SOURCE,
                "obra_geometria", Map.of("obraId", obraId, "categoria", after.categoria())
        );
        publish("GEOMETRIA_ENCERRADA", after, obraId, actorId, "ENCERRAR",
                state(before), state(after), baseVersion, reason);
    }

    private void registerObjectAndRelations(ObraGeometriaResponse feature, String obraId) {
        memoryService.registrarObjeto(
                "GEOMETRIA_OPERACIONAL", feature.id(), feature.id(),
                feature.categoria(), feature.status(), SOURCE,
                "obra_geometria", Map.of("obraId", obraId, "categoria", feature.categoria())
        );
        memoryService.registrarRelacaoAtiva(
                "GEOMETRIA_OPERACIONAL", feature.id(), "OBRA", obraId,
                "GEOREFERENCIA", SOURCE, "Geometria operacional vinculada à obra."
        );
        if (feature.objetoTipo() != null && feature.objetoId() != null) {
            memoryService.registrarRelacaoAtiva(
                    "GEOMETRIA_OPERACIONAL", feature.id(),
                    feature.objetoTipo(), feature.objetoId(),
                    "REPRESENTA", SOURCE, "Geometria autoritativa do objeto operacional."
            );
        }
    }

    private void closeRepresentedObjectRelation(ObraGeometriaResponse feature) {
        if (feature.objetoTipo() != null && feature.objetoId() != null) {
            memoryService.encerrarRelacaoAtiva(
                    "GEOMETRIA_OPERACIONAL", feature.id(),
                    feature.objetoTipo(), feature.objetoId(), "REPRESENTA"
            );
        }
    }

    private Map<String, Object> state(ObraGeometriaResponse feature) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", feature.id());
        state.put("categoria", feature.categoria());
        state.put("objetoTipo", feature.objetoTipo());
        state.put("objetoId", feature.objetoId());
        state.put("geometry", feature.geometry());
        state.put("properties", feature.properties());
        state.put("fonte", feature.fonte());
        state.put("status", feature.status());
        state.put("validoDesde", feature.validoDesde());
        state.put("validoAte", feature.validoAte());
        return state;
    }

    private void publish(
            String eventType,
            ObraGeometriaResponse feature,
            String obraId,
            String actorId,
            String operation,
            Map<String, Object> before,
            Map<String, Object> after,
            Long baseVersion,
            String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("actorId", actorId);
        payload.put("operation", operation);
        payload.put("beforeState", before);
        payload.put("afterState", after);
        payload.put("changedFields", after.keySet().stream()
                .filter(key -> !java.util.Objects.equals(before.get(key), after.get(key)))
                .toList());
        payload.put("baseVersion", baseVersion);
        payload.put("entityVersion", feature.versao());
        payload.put("reason", reason);
        payload.put("worksiteId", obraId);

        List<Map<String, Object>> related = feature.objetoTipo() == null
                ? List.of(Map.of("tipo", "OBRA", "id", obraId))
                : List.of(
                        Map.of("tipo", "OBRA", "id", obraId),
                        Map.of("tipo", feature.objetoTipo(), "id", feature.objetoId())
                );
        LocalDateTime now = LocalDateTime.now();
        memoryService.registrarEventoDetalhado(
                UUID.randomUUID().toString(), "GEOMETRIA_OPERACIONAL", feature.id(),
                eventType, SOURCE, obraId, null, null, related,
                "ONLINE", "SYNCED", now, now, 1, payload
        );
    }
}
