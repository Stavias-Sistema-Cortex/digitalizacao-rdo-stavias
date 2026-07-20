package com.projeto.cortex.frequencia;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record FrequenciaResumoResponse(
        String colaboradorId,
        String colaboradorNome,
        LocalDate inicio,
        LocalDate fim,
        List<JornadaResponse> jornadas,
        List<PresencaResponse> presencas,
        List<OcorrenciaResponse> ocorrencias,
        BancoHorasResumoResponse bancoHoras
) {
    public record JornadaResponse(
            String id,
            LocalDate dataJornada,
            int minutosPrevistos,
            LocalTime horaInicio,
            LocalTime horaFim,
            String origem
    ) {
    }

    public record PresencaResponse(
            String id,
            LocalDate dataPresenca,
            int minutosRegistrados,
            String origem,
            String status,
            String rdoId,
            String observacoes
    ) {
    }

    public record OcorrenciaResponse(
            String id,
            LocalDate dataOcorrencia,
            String tipo,
            int minutos,
            String status,
            String origem,
            String rdoId,
            String justificativa
    ) {
    }
}
