package com.projeto.cortex.integracoes;

import java.time.LocalDateTime;

/**
 * Minimal Academy source projection used exclusively to bootstrap the first
 * Córtex administrator. It deliberately excludes protected source identifiers.
 */
public record AcademyBootstrapUser(
        long sourceUserId,
        String nome,
        String email,
        boolean ativo,
        String idGrupo,
        String nomeGrupo,
        String idPerfil,
        String nomePerfil,
        LocalDateTime criadoEm
) {
}
