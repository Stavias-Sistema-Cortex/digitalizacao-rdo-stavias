package com.projeto.cortex.auth;

public record CpfFilterResponse(
        String algoritmo,
        int m,
        int k,
        int tamanho,
        String bits,
        String geradoEm
) {
}
