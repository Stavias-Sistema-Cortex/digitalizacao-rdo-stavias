package com.projeto.cortex.intelligence.stavia.knowledge.team;

import java.time.LocalDateTime;

public record ConfiguredTeamRecord(
        String teamId,
        String teamName,
        String worksiteId,
        String membershipId,
        String collaboratorId,
        String collaboratorName,
        String functionId,
        String functionName,
        boolean responsible,
        String assignedById,
        String assignedByName,
        LocalDateTime assignedAt,
        LocalDateTime updatedAt
) {
}
