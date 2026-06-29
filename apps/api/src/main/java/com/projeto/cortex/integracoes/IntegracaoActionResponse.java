package com.projeto.cortex.integracoes;

public record IntegracaoActionResponse(
        String integracao,
        String status,
        String mensagem
) {
}
