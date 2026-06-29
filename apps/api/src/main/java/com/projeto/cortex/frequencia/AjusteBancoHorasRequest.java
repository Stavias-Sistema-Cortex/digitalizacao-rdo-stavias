package com.projeto.cortex.frequencia;

import java.time.LocalDate;

public record AjusteBancoHorasRequest(
        LocalDate dataAjuste,
        LocalDate periodoInicio,
        LocalDate periodoFim,
        Integer minutosNovo,
        String motivo,
        String usuarioId,
        String status,
        String aprovadoPor
) {
}
