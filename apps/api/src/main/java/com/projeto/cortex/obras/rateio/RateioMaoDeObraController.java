package com.projeto.cortex.obras.rateio;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A porta do rateio de mão de obra por obra.
 *
 * <p>Leitura pura: nada aqui escreve, e por isso não há mutação a registrar no
 * catálogo operacional. O que sai é o que os RDOs do período já dizem, dentro
 * do alcance de quem pergunta.
 */
@RestController
public class RateioMaoDeObraController {

    private final RateioMaoDeObraService rateioMaoDeObraService;

    public RateioMaoDeObraController(
            RateioMaoDeObraService rateioMaoDeObraService
    ) {
        this.rateioMaoDeObraService = rateioMaoDeObraService;
    }

    @GetMapping("/api/obras/rateio-mao-de-obra")
    public RateioMaoDeObraResponse apontamentos(
            @RequestParam String inicio,
            @RequestParam String fim
    ) {
        return rateioMaoDeObraService.apontamentosDoPeriodo(
                dataDoParametro(inicio, "início"),
                dataDoParametro(fim, "fim")
        );
    }

    /**
     * Lê a data do parâmetro, ou diz qual deles está errado.
     *
     * <p>Deixar a exceção de formato subir daria 500 numa entrada malformada —
     * erro do servidor para o que é engano de quem chamou, e sem dizer qual dos
     * dois campos era o problema.
     */
    private static LocalDate dataDoParametro(String valor, String campo) {
        try {
            return LocalDate.parse(valor.trim());
        } catch (DateTimeParseException | NullPointerException erro) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Data de " + campo + " inválida; use o formato AAAA-MM-DD."
            );
        }
    }
}
