package com.projeto.cortex.frequencia;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/colaboradores/{colaboradorId}")
public class FrequenciaController {

    private final FrequenciaService frequenciaService;

    public FrequenciaController(FrequenciaService frequenciaService) {
        this.frequenciaService = frequenciaService;
    }

    @GetMapping("/frequencia")
    public FrequenciaResumoResponse buscarFrequencia(
            @PathVariable String colaboradorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate inicio,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fim
    ) {
        return frequenciaService.buscarResumo(
                colaboradorId,
                inicio,
                fim
        );
    }

    @PostMapping("/jornadas")
    @ResponseStatus(HttpStatus.CREATED)
    public FrequenciaResumoResponse.JornadaResponse registrarJornada(
            @PathVariable String colaboradorId,
            @RequestBody JornadaPlanejadaRequest request
    ) {
        return frequenciaService.registrarJornada(
                colaboradorId,
                request
        );
    }

    @PostMapping("/presencas")
    @ResponseStatus(HttpStatus.CREATED)
    public FrequenciaResumoResponse.PresencaResponse registrarPresenca(
            @PathVariable String colaboradorId,
            @RequestBody RegistroPresencaRequest request
    ) {
        return frequenciaService.registrarPresenca(
                colaboradorId,
                request
        );
    }

    @PostMapping("/ocorrencias")
    @ResponseStatus(HttpStatus.CREATED)
    public FrequenciaResumoResponse.OcorrenciaResponse registrarOcorrencia(
            @PathVariable String colaboradorId,
            @RequestBody OcorrenciaFrequenciaRequest request
    ) {
        return frequenciaService.registrarOcorrencia(
                colaboradorId,
                request
        );
    }

    @GetMapping("/banco-horas")
    public BancoHorasResumoResponse buscarBancoHoras(
            @PathVariable String colaboradorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate inicio,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fim
    ) {
        return frequenciaService.buscarBancoHoras(
                colaboradorId,
                inicio,
                fim
        );
    }

    @PostMapping("/banco-horas/ajustes")
    @ResponseStatus(HttpStatus.CREATED)
    public BancoHorasResumoResponse ajustarBancoHoras(
            @PathVariable String colaboradorId,
            @RequestBody AjusteBancoHorasRequest request
    ) {
        return frequenciaService.ajustarBancoHoras(
                colaboradorId,
                request
        );
    }
}
