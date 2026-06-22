package com.projeto.cortex.intelligence.stavia.knowledge.occurrence;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record OccurrenceRecord(
        String rdoId,
        String worksiteId,
        String rdoNumber,
        LocalDate rdoDate,
        String rdoStatus,
        String observations,
        LocalDateTime updatedAt
) {
}
