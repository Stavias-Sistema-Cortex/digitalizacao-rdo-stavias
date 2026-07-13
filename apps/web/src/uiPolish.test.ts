import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

function readCss(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

function rule(css: string, selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) {
    throw new Error(`Regra CSS ausente: ${selector}`);
  }
  const end = css.indexOf("\n}", start);
  if (end < 0) {
    throw new Error(`Regra CSS sem fechamento: ${selector}`);
  }
  return css.slice(start, end + 2);
}

const globalCss = readCss("./index.css");
const syncCss = readCss("./components/SyncStatusBanner.css");
const tarefasCss = readCss("./features/tarefas/TarefasPage.css");
const authenticatedCss = [
  globalCss,
  syncCss,
  readCss("./features/integracoes/IntegracoesPage.css"),
  readCss("./features/obras/gestao/gestaoObras.css"),
  readCss("./features/programacoes/ProgramacaoSemanalImport.css"),
  tarefasCss,
].join("\n");

describe("polimento visual da plataforma autenticada", () => {
  it("centraliza a paleta e a escala de raios aprovadas", () => {
    expect(rule(globalCss, ":root")).toContain("--color-text: #18231f;");
    expect(rule(globalCss, ":root")).toContain("--color-brand-teal: #124e4a;");
    expect(rule(globalCss, ":root")).toContain("--color-brand-yellow: #f2c800;");
    expect(rule(globalCss, ":root")).toContain("--radius-sm: 8px;");
    expect(rule(globalCss, ":root")).toContain("--radius-md: 12px;");
    expect(rule(globalCss, ":root")).toContain("--radius-lg: 16px;");
  });

  it("remove receitas de vidro da interface autenticada", () => {
    expect(authenticatedCss).not.toContain("--glass-");
    expect(authenticatedCss).not.toContain("backdrop-filter");
  });

  it("usa uma sidebar plana e métricas operacionais discretas", () => {
    expect(rule(globalCss, ".cortex-sidebar")).toContain(
      "background: var(--color-brand-teal);",
    );
    expect(rule(globalCss, ".cortex-sidebar")).not.toContain("radial-gradient");
    expect(rule(globalCss, ".metric-card")).toContain(
      "border-top: 3px solid var(--color-brand-yellow);",
    );
    expect(rule(globalCss, ".metric-card")).toContain(
      "background: var(--color-surface);",
    );
  });

  it("mantém foco visível para os controles principais", () => {
    expect(globalCss).toContain("button:focus-visible");
    expect(globalCss).toContain("outline: 3px solid var(--color-focus);");
  });

  it("usa foco amarelo de alto contraste nos controles da sidebar", () => {
    const sidebarFocus = rule(
      globalCss,
      [
        ".cortex-sidebar button:focus-visible,",
        ".cortex-sidebar a:focus-visible,",
        '.cortex-sidebar [role="separator"]:focus-visible',
      ].join("\n"),
    );

    expect(sidebarFocus).toContain(
      "outline: 3px solid var(--color-brand-yellow);",
    );
  });

  it("consome a escala aprovada nos raios do escopo compartilhado", () => {
    expect(rule(syncCss, ".sync-chip__action")).toContain(
      "border-radius: var(--radius-sm);",
    );
    expect(rule(globalCss, ".home-card")).toContain(
      "border-radius: var(--radius-lg);",
    );
    expect(rule(tarefasCss, ".tarefa-form-enviar")).toContain(
      "border-radius: var(--radius-sm);",
    );
  });

  it("tokeniza os filtros e delimita os cartões estáticos da Home", () => {
    expect(rule(globalCss, ".home-topbar")).toContain(
      "border-bottom: 1px solid var(--color-border);",
    );

    const chip = rule(globalCss, ".chip");
    expect(chip).toContain("border: 1px solid var(--color-border);");
    expect(chip).toContain("border-radius: var(--radius-sm);");
    expect(chip).toContain("background: var(--color-surface);");

    const ufSelect = rule(globalCss, ".home-uf-filter select");
    expect(ufSelect).toContain("border: 1px solid var(--color-border);");
    expect(ufSelect).toContain("border-radius: var(--radius-sm);");
    expect(ufSelect).toContain("background: var(--color-surface);");

    expect(rule(globalCss, ".chip--active")).toContain(
      "background: var(--color-brand-yellow);",
    );
    expect(rule(globalCss, ".home-obra-card")).toContain(
      "border: 1px solid var(--color-border);",
    );
    expect(rule(globalCss, ".home-card")).toContain(
      "border: 1px solid var(--color-border);",
    );
  });

  it("alinha à esquerda e contém a hierarquia operacional dos RDOs", () => {
    const filterLabel = rule(globalCss, ".rdo-filter-grid label");
    expect(filterLabel).toContain("letter-spacing: 0;");
    expect(filterLabel).toContain("text-transform: none;");

    const metric = rule(globalCss, ".metric-card");
    expect(metric).toContain("min-height: 82px;");
    expect(metric).toContain("padding: 14px 16px;");
    expect(metric).toContain("border-radius: var(--radius-md);");
    expect(rule(globalCss, ".metric-card span")).toContain(
      "text-align: left;",
    );
    expect(rule(globalCss, ".metric-card strong")).toContain(
      "text-align: left;",
    );

    const fact = rule(globalCss, ".rdo-fact");
    expect(fact).toContain("border: 1px solid #e7ebe8;");
    expect(fact).toContain("background: #f7f9f7;");
  });

  it("marca a obra selecionada sem alterar as cores semânticas do PDOR", () => {
    const surfaces = rule(globalCss, ".obras-list,\n.obras-detail");
    expect(surfaces).toContain("border: 1px solid var(--color-border);");
    expect(surfaces).toContain("border-radius: var(--radius-lg);");
    expect(surfaces).toContain("background: var(--color-surface);");

    const active = rule(globalCss, ".obras-list-item.active");
    expect(active).toContain("border-color: var(--color-border);");
    expect(active).toContain("background: #f7f9f7;");

    const marker = rule(globalCss, ".obras-list-item.active::before");
    expect(marker).toContain('content: "";');
    expect(marker).toContain("width: 3px;");
    expect(marker).toContain("background: var(--color-brand-yellow);");

    expect(rule(globalCss, ".obras-pdor-risk--alto")).toContain(
      "color: #a3322a;",
    );
  });

  it("evita overflow dos fluxos operacionais em telas estreitas", () => {
    const narrowCss = globalCss.slice(
      globalCss.lastIndexOf("@media (max-width: 620px)"),
    );

    expect(rule(narrowCss, ".home-dashboard")).toContain(
      "padding: 24px 14px 96px;",
    );
    expect(
      rule(
        narrowCss,
        [
          ".home-uf-filter,",
          "  .home-uf-filter select,",
          "  .home-obra-selector,",
          "  .home-obra-selector select",
        ].join("\n"),
      ),
    ).toContain("max-width: none;");
    expect(rule(narrowCss, ".home-obra-chart")).toContain("min-width: 0;");
    expect(
      rule(
        narrowCss,
        [
          ".rdo-command-actions,",
          "  .rdo-command-actions button,",
          "  .action-bar .button",
        ].join("\n"),
      ),
    ).toContain("width: 100%;");

    const obrasResponsive = globalCss.slice(
      globalCss.lastIndexOf("@media (max-width: 900px)"),
    );
    expect(rule(obrasResponsive, ".obras-workspace")).toContain(
      "grid-template-columns: 1fr;",
    );
  });
});
