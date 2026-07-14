package com.projeto.cortex.mensagens.api;

import java.util.List;

public record ConversationCreateRequest(
        String id,
        String tipo,
        String titulo,
        String obraId,
        String equipeId,
        List<String> participanteIds
) {
}
