package com.projeto.cortex.obras;

import com.projeto.cortex.memory.CortexOperationalMemoryService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ObraSyncEvento {

    static final String TIPO_ENTIDADE = "OBRA";
    static final String TIPO_EVENTO = "OBRA_ATUALIZADA";

    private ObraSyncEvento() {
    }

    static Map<String, Object> payload(Obra obra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("obraId", obra.getId());
        payload.put("codigoContrato", obra.getCodigoContrato());
        payload.put("nome", obra.getNome());
        payload.put("cliente", obra.getCliente());
        payload.put("cidade", obra.getCidade());
        payload.put("uf", obra.getUf());
        payload.put("rodovia", obra.getRodovia());
        payload.put("status", obra.getStatus());
        payload.put("observacoes", obra.getObservacoes());
        payload.put("latitude", obra.getLatitude());
        payload.put("longitude", obra.getLongitude());
        payload.put(
                "atualizadoEm",
                obra.getAtualizadoEm() == null
                        ? null
                        : obra.getAtualizadoEm().toString()
        );
        return payload;
    }

    static void registrarAtualizacao(
            CortexOperationalMemoryService memoryService,
            Obra obra
    ) {
        registrarObjetoEvidencias(memoryService, obra);
        memoryService.registrarEventoDetalhado(
                null,
                TIPO_ENTIDADE,
                obra.getId(),
                TIPO_EVENTO,
                "OBRAS",
                obra.getId(),
                null,
                null,
                List.of(),
                "ONLINE",
                "SYNCED",
                null,
                LocalDateTime.now(),
                1,
                payload(obra)
        );
    }

    static void registrarCriacao(
            CortexOperationalMemoryService memoryService,
            Obra obra,
            String actorId
    ) {
        registrarObjetoEvidencias(memoryService, obra);
        memoryService.registrarRelacaoAtiva(
                "PESSOA",
                actorId,
                "OBRA",
                obra.getId(),
                "CRIOU",
                "OBRAS",
                "Criação manual de obra autenticada."
        );
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> state = payload(obra);
        memoryService.registrarEventoAuditado(
                null,
                TIPO_ENTIDADE,
                obra.getId(),
                TIPO_EVENTO,
                "OBRAS",
                obra.getId(),
                null,
                actorId,
                List.of(Map.of(
                        "tipo", "PESSOA",
                        "id", actorId,
                        "relacao", "CRIOU"
                )),
                "ONLINE",
                "SYNCED",
                now,
                now,
                1,
                state,
                actorId,
                null,
                UUID.randomUUID().toString(),
                null,
                Map.of(),
                state,
                "SUCESSO",
                null
        );
    }

    private static void registrarObjetoEvidencias(
            CortexOperationalMemoryService memoryService,
            Obra obra
    ) {
        memoryService.registrarObjeto(
                TIPO_ENTIDADE,
                obra.getId(),
                obra.getCodigoContrato(),
                obra.getNome(),
                obra.getStatus(),
                "OBRAS"
        );
        memoryService.registrarEvidencias(
                TIPO_ENTIDADE,
                obra.getId(),
                "OBRAS",
                camposEvidencia(obra)
        );
    }

    /**
     * Fatos de campo da obra para a cortex_evidencia_operacional, dando à obra a
     * mesma paridade consultável na ontologia que RDO, colaborador e ativo já têm.
     * Campos nulos são ignorados pelo CortexOperationalMemoryService.
     */
    static Map<String, Object> camposEvidencia(Obra obra) {
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("codigo_contrato", obra.getCodigoContrato());
        campos.put("codigo_cw", obra.getCodigoCw());
        campos.put("codigo_interno", obra.getCodigoInterno());
        campos.put("nome", obra.getNome());
        campos.put("cliente", obra.getCliente());
        campos.put("descricao", obra.getDescricao());
        campos.put("cidade", obra.getCidade());
        campos.put("uf", obra.getUf());
        campos.put("rodovia", obra.getRodovia());
        campos.put("status", obra.getStatus());
        campos.put("latitude", obra.getLatitude());
        campos.put("longitude", obra.getLongitude());
        campos.put("observacoes", obra.getObservacoes());
        return campos;
    }
}
