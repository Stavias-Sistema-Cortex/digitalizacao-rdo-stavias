package com.projeto.cortex.financeiro;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ItemContratualResponse(
        String id,
        String obraId,
        String contrato,
        String codigoItem,
        String descricao,
        String unidadeMedida,
        BigDecimal quantidadeContratada,
        BigDecimal precoUnitario,
        BigDecimal valorTotal,
        LocalDate vigenciaInicio,
        LocalDate vigenciaFim,
        Integer versao,
        String status,
        String fonte,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm
) {
}
