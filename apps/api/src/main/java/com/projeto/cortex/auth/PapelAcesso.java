package com.projeto.cortex.auth;

import java.util.Locale;
import java.util.Optional;

/**
 * Papel de acesso do colaborador no Córtex.
 *
 * <p>O modelo começa com dois papéis e permanece extensível: novos papéis podem
 * ser acrescentados sem espalhar condicionais {@code if (papel == ALFA)} pelo
 * código — a decisão de autorização fica centralizada em {@link CurrentUserService}.
 *
 * <ul>
 *   <li>{@link #ALFA}: acesso administrativo global (proprietário/gestor).</li>
 *   <li>{@link #BETA}: acesso operacional restrito às obras vinculadas.</li>
 * </ul>
 */
public enum PapelAcesso {

    ALFA,
    BETA;

    /**
     * Converte o valor persistido em {@code colaborador.papel_acesso}. Valores
     * ausentes ou desconhecidos permanecem no menor privilégio ({@link #BETA})
     * e nunca são elevados a partir de textos legados de perfil ou grupo.
     */
    public static PapelAcesso fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return BETA;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (PapelAcesso papel : values()) {
            if (papel.name().equals(normalized)) {
                return papel;
            }
        }

        return BETA;
    }

    /** Parses only canonical persisted values; unknown data denies access. */
    public static Optional<PapelAcesso> fromPersistedExact(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (PapelAcesso papel : values()) {
            if (papel.name().equals(value)) {
                return Optional.of(papel);
            }
        }
        return Optional.empty();
    }
}
