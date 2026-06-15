package com.projeto.cortex.programacoes;

public record ProgramacaoSeedImportResult(
        String status,
        String caminhoArquivo,
        int registrosLidos,
        int registrosInseridos,
        int registrosIgnorados,
        int registrosComErro
) {
}
