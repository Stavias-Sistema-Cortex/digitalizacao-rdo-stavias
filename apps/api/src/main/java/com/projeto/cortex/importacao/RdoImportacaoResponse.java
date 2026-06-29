package com.projeto.cortex.importacao;

import java.time.LocalDateTime;
import java.util.List;

public record RdoImportacaoResponse(
        String id,
        String nomeArquivo,
        String hashArquivo,
        long tamanhoBytes,
        String layoutDetectado,
        String status,
        String estrategiaDuplicidade,
        String versaoMapeamento,
        int quantidadeProcessada,
        int quantidadeImportada,
        int quantidadeRejeitada,
        int quantidadeDuplicada,
        LocalDateTime criadoEm,
        LocalDateTime confirmadoEm,
        List<RdoImportacaoLinhaResponse> linhas
) {
}
