package com.projeto.cortex.obras;

import java.util.LinkedHashMap;
import java.util.Map;

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
}
