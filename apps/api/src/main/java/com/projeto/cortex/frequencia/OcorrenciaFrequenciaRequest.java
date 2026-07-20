package com.projeto.cortex.frequencia;

import java.time.LocalDate;

public record OcorrenciaFrequenciaRequest(
        LocalDate dataOcorrencia,
        String tipo,
        Integer minutos,
        String status,
        String origem,
        String rdoId,
        String justificativa,
        String criadoPor,
        String validadoPor
) {
}
