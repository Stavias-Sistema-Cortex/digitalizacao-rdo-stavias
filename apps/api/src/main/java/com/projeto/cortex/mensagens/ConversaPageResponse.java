package com.projeto.cortex.mensagens;

import java.util.List;

public record ConversaPageResponse(
        List<ConversaSummaryResponse> items,
        int page,
        int size,
        long totalElements
) {
}
