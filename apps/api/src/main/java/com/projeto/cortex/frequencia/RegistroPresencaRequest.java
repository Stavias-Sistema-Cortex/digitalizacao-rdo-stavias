package com.projeto.cortex.frequencia;

import java.time.LocalDate;

public record RegistroPresencaRequest(
        LocalDate dataPresenca,
        Integer minutosRegistrados,
        String origem,
        String status,
        String rdoId,
        String observacoes
) {
}
