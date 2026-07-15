package com.projeto.cortex.mensagens;

public record MensagemReferenciaRequest(
        String id,
        String tipoObjeto,
        String objetoId
) {
}
