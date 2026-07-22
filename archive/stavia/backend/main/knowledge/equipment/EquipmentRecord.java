package com.projeto.cortex.intelligence.stavia.knowledge.equipment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record EquipmentRecord(
        String equipmentRecordId,
        String worksiteId,
        String rdoId,
        String rdoNumber,
        LocalDate rdoDate,
        String rdoStatus,
        String assetId,
        String recordedPrefix,
        String recordedDescription,
        String recordedType,
        String linkType,
        BigDecimal quantity,
        LocalTime startTime,
        LocalTime endTime,
        String observations,
        String registeredExternalCode,
        String registeredName,
        String registeredCategory,
        Boolean registeredActive,
        LocalDateTime updatedAt
) {
}
