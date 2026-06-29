package com.projeto.cortex.frequencia;

import java.time.LocalDate;
import java.time.LocalTime;

public record JornadaPlanejadaRequest(
        LocalDate dataJornada,
        Integer minutosPrevistos,
        LocalTime horaInicio,
        LocalTime horaFim,
        String origem
) {
}
