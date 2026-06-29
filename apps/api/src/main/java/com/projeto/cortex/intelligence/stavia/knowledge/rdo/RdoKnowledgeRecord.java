package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record RdoKnowledgeRecord(
        String id,
        String worksiteId,
        String worksiteCode,
        String programacaoId,
        String programacaoChave,
        LocalDate programacaoData,
        String programacaoServico,
        String programacaoStatus,
        String numeroRdo,
        LocalDate dataRdo,
        String turno,
        String status,
        String observacoes,
        int totalMaoObra,
        int totalEquipamentos,
        int totalMateriais,
        int totalControlesGeometricos,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
}
