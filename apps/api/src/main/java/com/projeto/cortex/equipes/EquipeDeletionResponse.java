package com.projeto.cortex.equipes;

public record EquipeDeletionResponse(
        String equipeId,
        String nome,
        int membrosRemovidos
) {
}
