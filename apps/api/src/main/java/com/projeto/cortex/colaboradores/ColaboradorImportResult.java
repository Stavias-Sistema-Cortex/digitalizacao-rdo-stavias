package com.projeto.cortex.colaboradores;

public record ColaboradorImportResult(
        String idExecucaoSync,
        String bancoOrigem,
        String tabelaOrigem,
        String status,
        int registrosLidos,
        int registrosProcessados,
        int registrosInseridos,
        int registrosAtualizados,
        int registrosDesativados,
        String mensagemErro
) {}
