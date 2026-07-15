package com.projeto.cortex.mensagens;

import java.util.List;

public record ConversaCreateRequest(
        String id,
        String tipo,
        String titulo,
        String obraId,
        String equipeId,
        List<String> participanteIds
) {
}
