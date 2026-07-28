package com.projeto.cortex.colaboradores;

import java.util.List;

public record ColaboradoresAutorizadosObraResponse(
        List<String> ids,
        int total,
        boolean complete
) {
}
