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
        int registrosDesativados, // Número de registros desativados para o banco de dados de destino
        String mensagemErro
) {}
