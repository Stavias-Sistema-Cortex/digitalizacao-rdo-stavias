package com.projeto.cortex.financeiro;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ItemContratualRequest(
        String contrato,
        String codigoItem,
        String descricao,
        String unidadeMedida,
        BigDecimal quantidadeContratada,
        BigDecimal precoUnitario,
        LocalDate vigenciaInicio,
        LocalDate vigenciaFim,
        Integer versao,
        String status,
        String fonte
) {
}
