package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record RdoAttributeRecord(
        String id,
        String worksiteId,
        String worksiteCode,
        String numeroRdo,
        LocalDate dataRdo,
        String status,
        String fonteCriacao,
        String cliente,
        String cidade,
        String contrato,
        String rodovia,
        String uf,
        String kmInicialProgramado,
        String kmFinalProgramado,
        String kmInicialInterditado,
        String kmFinalInterditado,
        String turno,
        LocalTime horaInicio,
        LocalTime horaFim,
        String condicaoManha,
        String condicaoTarde,
        String condicaoNoite,
        BigDecimal pluviometriaMm,
        String preenchidoPor,
        String apontadorRdo,
        String encarregadoObra,
        String fiscalizacaoCampo,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm,
        LocalDateTime enviadoEm,
        LocalDateTime aprovadoEm
) {
}
