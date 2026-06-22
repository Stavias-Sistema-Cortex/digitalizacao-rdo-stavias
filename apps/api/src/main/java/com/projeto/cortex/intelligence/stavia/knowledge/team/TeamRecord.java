package com.projeto.cortex.intelligence.stavia.knowledge.team;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record TeamRecord(
        String laborRecordId,
        String worksiteId,
        String rdoId,
        String rdoNumber,
        LocalDate rdoDate,
        String rdoStatus,
        String collaboratorId,
        String recordedName,
        String registeredCollaboratorName,
        String role,
        String linkType,
        BigDecimal quantity,
        LocalTime startTime,
        LocalTime endTime,
        LocalDateTime updatedAt
) {
}
