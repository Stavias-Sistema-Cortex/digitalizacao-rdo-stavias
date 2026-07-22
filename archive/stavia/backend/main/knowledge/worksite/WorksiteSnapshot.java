package com.projeto.cortex.intelligence.stavia.knowledge.worksite;

import java.time.LocalDateTime;

public record WorksiteSnapshot(
        String id,
        String codigoContrato,
        String codigoCw,
        String codigoInterno,
        String nome,
        String cliente,
        String descricao,
        String cidade,
        String uf,
        String rodovia,
        String status,
        String fonteCriacao,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm,
        LocalDateTime arquivadoEm,
        long versaoLinha
) {
}
