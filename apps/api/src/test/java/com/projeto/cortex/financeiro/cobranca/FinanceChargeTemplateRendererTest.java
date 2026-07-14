package com.projeto.cortex.financeiro.cobranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FinanceChargeTemplateRendererTest {

    private final FinanceChargeTemplateRenderer renderer =
            new FinanceChargeTemplateRenderer();

    @Test
    void rendersOnlyExplicitlyAllowedRealValuesAndProducesStableHash() {
        FinanceChargeTemplateRenderer.RenderedEmail rendered = renderer.render(
                "Cobrança {{numeroDocumento}} — {{obraNome}}",
                "Olá, {{fornecedorNome}}. Valor aberto: {{valorAberto}}.",
                Set.of(
                        "numeroDocumento",
                        "obraNome",
                        "fornecedorNome",
                        "valorAberto"
                ),
                Map.of(
                        "numeroDocumento", "NF-42",
                        "obraNome", "Obra Exemplo",
                        "fornecedorNome", "Fornecedor Exemplo",
                        "valorAberto", "R$ 125,50"
                )
        );

        assertThat(rendered.subject())
                .isEqualTo("Cobrança NF-42 — Obra Exemplo");
        assertThat(rendered.textBody())
                .isEqualTo("Olá, Fornecedor Exemplo. Valor aberto: R$ 125,50.");
        assertThat(rendered.previewHash()).matches("[a-f0-9]{64}");
        assertThat(renderer.render(
                "Cobrança {{numeroDocumento}} — {{obraNome}}",
                "Olá, {{fornecedorNome}}. Valor aberto: {{valorAberto}}.",
                Set.of(
                        "numeroDocumento",
                        "obraNome",
                        "fornecedorNome",
                        "valorAberto"
                ),
                Map.of(
                        "numeroDocumento", "NF-42",
                        "obraNome", "Obra Exemplo",
                        "fornecedorNome", "Fornecedor Exemplo",
                        "valorAberto", "R$ 125,50"
                )
        ).previewHash()).isEqualTo(rendered.previewHash());
    }

    @Test
    void rejectsUnknownPlaceholderEvenWhenAValueWasSupplied() {
        assertThatThrownBy(() -> renderer.render(
                "Cobrança {{senhaSmtp}}",
                "Corpo",
                Set.of("obraNome"),
                Map.of("senhaSmtp", "segredo")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não permitida")
                .hasMessageNotContaining("segredo");
    }

    @Test
    void rejectsMissingOrBlankRealValueInsteadOfInventingContent() {
        assertThatThrownBy(() -> renderer.render(
                "Cobrança {{numeroDocumento}}",
                "Olá, {{fornecedorNome}}.",
                Set.of("numeroDocumento", "fornecedorNome"),
                Map.of("numeroDocumento", "NF-42")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fornecedorNome");
    }

    @Test
    void rejectsHeaderInjectionAndUnresolvedTemplateSyntax() {
        assertThatThrownBy(() -> renderer.render(
                "Cobrança\r\nBcc: attacker@example.invalid {{obraNome}}",
                "Corpo",
                Set.of("obraNome"),
                Map.of("obraNome", "Obra Exemplo")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assunto");

        assertThatThrownBy(() -> renderer.render(
                "Cobrança {{ obraNome }}",
                "Corpo",
                Set.of("obraNome"),
                Map.of("obraNome", "Obra Exemplo")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("template");
    }
}
