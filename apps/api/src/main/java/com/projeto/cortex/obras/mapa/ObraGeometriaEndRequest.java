package com.projeto.cortex.obras.mapa;

import java.time.LocalDateTime;

public record ObraGeometriaEndRequest(
        Long baseVersao,
        String motivo,
        LocalDateTime validoAte
) {
}
