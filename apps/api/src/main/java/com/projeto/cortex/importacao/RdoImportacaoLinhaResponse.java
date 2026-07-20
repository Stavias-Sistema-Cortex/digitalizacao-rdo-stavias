package com.projeto.cortex.importacao;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

public record RdoImportacaoLinhaResponse(
        String id,
        String aba,
        int numeroLinha,
        String status,
        String obraId,
        String numeroRdo,
        LocalDate dataRdo,
        String rdoId,
        JsonNode payloadOriginal,
        JsonNode payloadNormalizado,
        List<RdoImportacaoErroResponse> erros
) {
}
