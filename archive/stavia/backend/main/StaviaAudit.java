package com.projeto.cortex.intelligence.stavia;

/**
 * Registro de auditoria/observabilidade de uma consulta à Stav.IA (§16).
 *
 * <p>Estruturado e <strong>seguro</strong>: contém usuário e escopo de obra
 * (ids internos), decisão de autorização, intenção, resultado, contagens de
 * fontes/avisos, duração e versões de motor/prompt. <strong>Nunca</strong>
 * inclui o texto da pergunta ou da resposta, o conteúdo das evidências, nomes,
 * senhas, tokens ou segredos — evitando registro indiscriminado de dados
 * pessoais ou de prompts completos.
 */
public record StaviaAudit(
        String userId,
        String worksiteId,
        boolean authorized,
        String intent,
        String outcome,
        int citedSources,
        int consultedSources,
        int warnings,
        long durationMs,
        String engineVersion,
        String promptVersion
) {

    /** Linha de log estável em {@code key=value}, apenas com dados não sensíveis. */
    public String toLogLine() {
        return "stavia.audit"
                + " user=" + safe(userId)
                + " worksite=" + safe(worksiteId)
                + " authorized=" + authorized
                + " intent=" + safe(intent)
                + " outcome=" + safe(outcome)
                + " citedSources=" + citedSources
                + " consultedSources=" + consultedSources
                + " warnings=" + warnings
                + " durationMs=" + durationMs
                + " engineVersion=" + safe(engineVersion)
                + " promptVersion=" + safe(promptVersion);
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? "-" : value.trim();
    }
}
