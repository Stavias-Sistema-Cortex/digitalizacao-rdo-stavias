package com.projeto.cortex.obras;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ObraRelacionadaResponse(
        String id,
        String codigoContrato,
        String nome,
        String cliente,
        String cidade,
        String uf,
        String rodovia,
        String status,
        String observacoes,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal valorContratual,
        LocalDateTime atualizadoEm,
        long versaoLinha
) {
}
