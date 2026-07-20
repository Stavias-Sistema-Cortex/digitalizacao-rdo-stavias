package com.projeto.cortex.integracoes;

public record IntegracaoStatusResponse(
        String id,
        String nome,
        String estado,
        String ultimaSincronizacao,
        Long duracaoSegundos,
        int registrosLidos,
        int registrosCriados,
        int registrosAtualizados,
        int registrosDesativados,
        String erro,
        String proximaSincronizacao,
        String defasagem
) {
}
