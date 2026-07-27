package com.projeto.cortex.intelligence.stavia;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A auditoria da Stav.IA (§16) produz uma linha estruturada apenas com dados
 * não sensíveis (ids, contagens, versões) — por construção não carrega texto de
 * pergunta/resposta nem segredos.
 */
class StaviaAuditTest {

    @Test
    void formataLinhaEstruturadaComOsCamposDeAuditoria() {
        StaviaAudit audit = new StaviaAudit(
                "user-1", "obra-1", true, "CONSULTAR_RDO", "FATO",
                2, 3, 0, 42, "engine-1", "prompt-1"
        );

        assertThat(audit.toLogLine())
                .startsWith("stavia.audit")
                .contains("user=user-1")
                .contains("worksite=obra-1")
                .contains("authorized=true")
                .contains("intent=CONSULTAR_RDO")
                .contains("outcome=FATO")
                .contains("citedSources=2")
                .contains("consultedSources=3")
                .contains("warnings=0")
                .contains("durationMs=42")
                .contains("engineVersion=engine-1")
                .contains("promptVersion=prompt-1");
    }

    @Test
    void consultaNegadaRegistraAuthorizedFalse() {
        StaviaAudit audit = new StaviaAudit(
                "user-1", "obra-x", false, "DESCONHECIDA",
                "INFORMACAO_INSUFICIENTE", 0, 0, 1, 5, "e", "p"
        );

        assertThat(audit.toLogLine()).contains("authorized=false");
    }

    @Test
    void camposEmBrancoViramTracoEmVezDeVazarNull() {
        StaviaAudit audit = new StaviaAudit(
                null, "  ", true, null, "FATO", 0, 0, 0, 0, null, null
        );

        assertThat(audit.toLogLine())
                .contains("user=-")
                .contains("worksite=-")
                .contains("intent=-")
                .contains("engineVersion=-")
                .contains("promptVersion=-")
                .doesNotContain("null");
    }
}
