package com.projeto.cortex.mensagens.api;

public record ParticipantChangeRequest(
        String colaboradorId,
        String papel
) {
}
