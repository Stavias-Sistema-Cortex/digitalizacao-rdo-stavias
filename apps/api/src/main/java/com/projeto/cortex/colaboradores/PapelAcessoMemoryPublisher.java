package com.projeto.cortex.colaboradores;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PapelAcessoMemoryPublisher {

    private static final String SOURCE = "GESTAO_ACESSO";

    private final CortexOperationalMemoryService memoryService;

    public PapelAcessoMemoryPublisher(CortexOperationalMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public void papelAlterado(
            PapelAcessoUpdateResponse before,
            PapelAcessoUpdateResponse after,
            String actorId,
            long baseVersion,
            String reason
    ) {
        Map<String, Object> beforeState = state(before);
        Map<String, Object> afterState = state(after);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("actorId", actorId);
        payload.put("operation", "ALTERAR_PAPEL_ACESSO");
        payload.put("beforeState", beforeState);
        payload.put("afterState", afterState);
        payload.put("changedFields", List.of("papelAcesso"));
        payload.put("baseVersion", baseVersion);
        payload.put("entityVersion", after.versaoLinha());
        payload.put("reason", reason);

        String eventType = "ALFA".equals(after.papelAcesso())
                ? "ACESSO_ALFA_CONCEDIDO"
                : "ACESSO_ALFA_REVOGADO";
        LocalDateTime now = LocalDateTime.now();
        memoryService.registrarEventoDetalhado(
                UUID.randomUUID().toString(),
                "COLABORADOR",
                after.colaboradorId(),
                eventType,
                SOURCE,
                null,
                null,
                after.colaboradorId(),
                List.of(Map.of("tipo", "COLABORADOR", "id", actorId)),
                "ONLINE",
                "SYNCED",
                now,
                now,
                1,
                payload
        );
    }

    private Map<String, Object> state(PapelAcessoUpdateResponse response) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("colaboradorId", response.colaboradorId());
        state.put("papelAcesso", response.papelAcesso());
        return state;
    }
}
