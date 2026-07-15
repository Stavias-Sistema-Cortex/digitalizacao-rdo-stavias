package com.projeto.cortex.mensagens;

import java.util.List;

public record MensagemPageResponse(
        List<MensagemResponse> items,
        MensagemCursor proximoCursor,
        boolean hasMore
) {
}
