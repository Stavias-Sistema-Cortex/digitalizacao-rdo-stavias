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
});
