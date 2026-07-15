package com.projeto.cortex.mensagens;

public record ConversaFilter(
        String texto,
        String obraId,
        String equipeId,
        Integer page,
        Integer size
) {
}
