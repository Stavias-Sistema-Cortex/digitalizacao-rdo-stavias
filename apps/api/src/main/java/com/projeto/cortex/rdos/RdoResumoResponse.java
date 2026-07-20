package com.projeto.cortex.rdos;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record RdoResumoResponse(
        String id,
        String obraId,
        String programacaoId,

        String numeroRdo,
        LocalDate dataRdo,
        String diaSemana,

        String cliente,
        String contrato,
        String rodovia,
        String cidade,
        String uf,

        String turno,
        String status,

        int totalMaoObra,
        int totalEquipamentos,
        int totalMateriais,
        int totalControlesGeometricos,

        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
}
