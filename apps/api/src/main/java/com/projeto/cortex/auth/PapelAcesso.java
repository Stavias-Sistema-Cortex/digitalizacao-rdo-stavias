package com.projeto.cortex.auth;

import java.text.Normalizer;
import java.util.Locale;

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
     * Converte o valor persistido em {@code colaborador.papel_acesso}. Retorna
     * {@code null} quando o valor é ausente ou desconhecido, para que o chamador
     * decida o comportamento de fallback de forma explícita.
     */
    public static PapelAcesso fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (PapelAcesso papel : values()) {
            if (papel.name().equals(normalized)) {
                return papel;
            }
        }

        return null;
    }

    /**
     * Heurística de compatibilidade: deriva o papel a partir do texto de perfil
     * e grupo importados da Academy. Usada apenas como fallback quando o papel
     * explícito ainda não foi definido (ex.: colaborador recém-importado antes
     * de o backfill/atribuição rodar). Mantém o mesmo critério histórico de
     * "administrador" para não alterar o acesso de quem já era administrador.
     */
    public static PapelAcesso fromPerfilGrupo(String nomePerfil, String nomeGrupo) {
        if (contemAdmin(nomePerfil) || contemAdmin(nomeGrupo)) {
            return ALFA;
        }

        return BETA;
    }

    private static boolean contemAdmin(String value) {
        String normalized = normalizar(value);
        return normalized.contains("admin")
                || normalized.contains("administrador")
                || normalized.contains("administradora");
    }

    private static String normalizar(String value) {
        if (value == null) {
            return "";
        }

        String semAcento = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT);
    }
}
