package com.projeto.cortex.frequencia;

import java.time.LocalDate;
import java.util.List;

public record BancoHorasResumoResponse(
        String colaboradorId,
        String colaboradorNome,
        LocalDate periodoInicio,
        LocalDate periodoFim,
        int minutosSaldoAnterior,
        int minutosCredito,
        int minutosDebito,
        int minutosAjuste,
        int minutosSaldoAtual,
        List<AjusteResponse> ajustes
) {
    public record AjusteResponse(
            String id,
            LocalDate dataAjuste,
            int minutosAnterior,
            int minutosNovo,
            int minutosDelta,
            String motivo,
            String usuarioId,
            String status,
            String aprovadoPor
    ) {
    }
}
