package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record RdoAttributeRecord(
        String id,
        String worksiteId,
        String worksiteCode,
        String numeroRdo,
        LocalDate dataRdo,
        String status,
        String fonteCriacao,
        String turno,
        String condicaoManha,
        String condicaoTarde,
        String condicaoNoite,
        BigDecimal pluviometriaMm,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm,
        LocalDateTime enviadoEm,
        LocalDateTime aprovadoEm
) {
}
