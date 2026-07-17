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

function lastRule(css: string, selector: string): string {
  const start = css.lastIndexOf(`${selector} {`);
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
const integracoesCss = readCss("./features/integracoes/IntegracoesPage.css");
const gestaoObrasCss = readCss("./features/obras/gestao/gestaoObras.css");
const programacaoCss = readCss(
  "./features/programacoes/ProgramacaoSemanalImport.css",
);
const staviaCss = readCss("./features/stavia/StaviaPanel.css");
const institutionalCss = readCss(
  "./components/institutional/institutional.css",
);
const authenticatedCss = [
  globalCss,
  syncCss,
  integracoesCss,
  gestaoObrasCss,
  programacaoCss,
  tarefasCss,
].join("\n");

describe("polimento visual da plataforma autenticada", () => {
  it("centraliza a paleta e a escala de raios aprovadas", () => {
    expect(rule(globalCss, ":root")).toContain("--color-ink: #111312;");
    expect(rule(globalCss, ":root")).toContain("--color-graphite: #292d2b;");
    expect(rule(globalCss, ":root")).toContain("--color-text: var(--color-ink);");
    expect(rule(globalCss, ":root")).toContain("--color-brand-teal: #124e4a;");
    expect(rule(globalCss, ":root")).toContain("--color-brand-yellow: #f2c800;");
    expect(rule(globalCss, ":root")).toContain("--radius-control: 4px;");
    expect(rule(globalCss, ":root")).toContain("--radius-container: 4px;");
    expect(rule(globalCss, ":root")).toContain("--radius-sm: var(--radius-control);");
    expect(rule(globalCss, ":root")).toContain("--radius-lg: var(--radius-container);");
  });

  it("mantém status, dados e rastreios na escala de corpo Poppins", () => {
    expect(rule(institutionalCss, ".institutional-status")).toContain(
      "font-weight: 500;",
    );
    expect(
      rule(institutionalCss, ".institutional-sync-state__facts dd"),
    ).toContain("font-weight: 500;");
    expect(rule(institutionalCss, ".trace-reference")).toContain(
      "font-weight: 500;",
    );

    const traceId = rule(
      institutionalCss,
      ".trace-reference__id",
    );

    expect(traceId).not.toContain("font-family:");
    expect(traceId).toContain("font-variant-numeric: tabular-nums;");
  });

  it("remove receitas de vidro da interface autenticada", () => {
    expect(authenticatedCss).not.toContain("--glass-");
    expect(authenticatedCss).not.toContain("backdrop-filter");
  });

  it("usa uma sidebar institucional em preto e verde e métricas operacionais discretas", () => {
    expect(rule(globalCss, ".cortex-sidebar")).toContain(
      "background: linear-gradient(",
    );
    expect(rule(globalCss, ".cortex-sidebar")).toContain("#111312 0%");
    expect(rule(globalCss, ".cortex-sidebar")).toContain(
      "var(--color-brand-teal) 100%",
    );
    expect(rule(globalCss, ".cortex-sidebar")).not.toContain("radial-gradient");
    expect(rule(globalCss, ".metric-card")).toContain(
      "background: var(--color-surface);",
    );
  });

  it("enquadra métricas RDO como registro branco com acentos estruturais", () => {
    const metricCard = lastRule(globalCss, ".metric-card");
    expect(metricCard).toContain(
      "border: 2px solid var(--color-ink);",
    );
    expect(metricCard).toContain("border-radius: var(--radius-control);");
    expect(metricCard).not.toContain("border-top:");
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
    expect(lastRule(globalCss, ".home-card")).toContain(
      "border-radius: var(--radius-container);",
    );
    expect(rule(tarefasCss, ".tarefa-form-enviar")).toContain(
      "border-radius: var(--radius-sm);",
    );
  });

  it("tokeniza os filtros e delimita os cartões estáticos da Home", () => {
    expect(lastRule(globalCss, ".home-topbar")).toContain(
      "border-bottom-color: var(--color-ink);",
    );

    const chip = rule(globalCss, ".chip");
    expect(chip).toContain("border: 1px solid var(--color-border);");
    expect(chip).toContain("border-radius: var(--radius-control);");
    expect(chip).toContain("background: var(--color-surface);");

    const ufSelect = rule(globalCss, ".home-uf-filter select");
    expect(ufSelect).toContain("border: 1px solid var(--color-border);");
    expect(ufSelect).toContain("border-radius: var(--radius-sm);");
    expect(ufSelect).toContain("background: var(--color-surface);");

    expect(lastRule(globalCss, ".chip--active")).toContain(
      "background: var(--color-ink);",
    );
    expect(rule(globalCss, ".home-obra-card")).toContain(
      "border: 2px solid var(--color-ink);",
    );
    expect(rule(globalCss, ".home-card")).toContain(
      "border: 1px solid var(--color-border);",
    );
  });

  it("mantém contraste legível nas notas pequenas da Home", () => {
    expect(rule(globalCss, ".home-card-muted")).toContain(
      "color: var(--color-muted);",
    );
    expect(rule(globalCss, ".home-updated-at")).toContain(
      "color: var(--color-muted);",
    );
  });

  it("alinha à esquerda e contém a hierarquia operacional dos RDOs", () => {
    const filterLabel = lastRule(globalCss, ".rdo-filter-grid label");
    expect(filterLabel).toContain("font-weight: 500;");
    expect(filterLabel).toContain("text-transform: uppercase;");

    const metric = lastRule(globalCss, ".metric-card");
    expect(metric).toContain("min-height: 76px;");
    expect(metric).toContain("padding: 12px 14px;");
    expect(metric).toContain("border-radius: var(--radius-control);");
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

  it("dá escala própria ao comando RDO e alvos práticos aos controles", () => {
    expect(rule(globalCss, ".rdo-command-band h1")).toContain(
      "font-size: clamp(1.9rem, 3vw, 2.4rem);",
    );
    expect(rule(syncCss, ".sync-chip__button")).toContain("width: 40px;");
    expect(rule(syncCss, ".sync-chip__button")).toContain("height: 40px;");
    expect(rule(globalCss, ".avatar-button")).toContain("width: 40px;");
    expect(rule(globalCss, ".avatar-button")).toContain("height: 40px;");
    expect(rule(globalCss, "\n.sidebar-footer button")).toContain(
      "min-height: 40px;",
    );
    expect(
      rule(globalCss, ".rdo-filter-grid input,\n.rdo-filter-grid select"),
    ).toContain("min-height: 40px;");
  });

  it("compacta o shell móvel em grades com rótulos e alvos de 40px", () => {
    const mobileShellCss = globalCss.slice(
      globalCss.indexOf("@media (max-width: 900px)"),
      globalCss.indexOf("@media (max-width: 620px)"),
    );

    expect(
      rule(
        mobileShellCss,
        "  .cortex-shell,\n  .cortex-shell--collapsed",
      ),
    ).toContain("align-content: start;");
    expect(rule(mobileShellCss, "  .cortex-sidebar")).toContain("gap: 8px;");
    expect(rule(mobileShellCss, "  .sidebar-brand")).toContain(
      "padding: 0 100px 8px 8px;",
    );
    expect(rule(mobileShellCss, "  .sidebar-brand-lockup")).toContain(
      "max-width: 180px;",
    );
    expect(rule(mobileShellCss, "  .sidebar-nav")).toContain(
      "grid-template-columns: repeat(4, minmax(0, 1fr));",
    );
    expect(rule(mobileShellCss, "  .sidebar-footer")).toContain(
      "grid-template-columns: repeat(3, minmax(0, 1fr));",
    );
    expect(rule(mobileShellCss, "  .sidebar-nav-item")).toContain(
      "min-height: 40px;",
    );
    expect(rule(mobileShellCss, "  .sidebar-footer button")).toContain(
      "min-height: 40px;",
    );
  });

  it("limita o lockup abaixo de 340px para não invadir o cluster fixo", () => {
    const extraNarrowCss = globalCss.slice(
      globalCss.indexOf("@media (max-width: 340px)"),
      globalCss.indexOf("@media (max-width: 620px)"),
    );

    expect(rule(extraNarrowCss, "  .sidebar-brand-lockup")).toContain(
      "max-width: 160px;",
    );
  });

  it("reserva zonas móveis para o cluster e o launcher sem colisões", () => {
    const narrowCss = globalCss.slice(
      globalCss.lastIndexOf("@media (max-width: 620px)"),
    );
    const floatingControls = rule(narrowCss, ".floating-controls");
    expect(floatingControls).toContain("top: 12px;");
    expect(floatingControls).toContain("right: 12px;");
    expect(
      rule(
        narrowCss,
        ".home-dashboard,\n  .rdo-dashboard,\n  .obras-page",
      ),
    ).toContain("padding-top: 84px;");

    const tarefasNarrowCss = tarefasCss.slice(
      tarefasCss.lastIndexOf("@media (max-width: 620px)"),
    );
    expect(rule(tarefasNarrowCss, ".tarefas-page")).toContain(
      "padding-top: 84px;",
    );
    expect(rule(tarefasNarrowCss, ".tarefas-page")).toContain(
      "padding-bottom: calc(120px + env(safe-area-inset-bottom));",
    );

    const staviaNarrowCss = staviaCss.slice(
      staviaCss.lastIndexOf("@media (max-width: 560px)"),
    );
    const launcher = rule(staviaNarrowCss, ".stavia-launcher");
    expect(launcher).toContain("position: absolute;");
    expect(launcher).toContain("right: 12px;");
    expect(launcher).toContain("top: 238px;");
    expect(launcher).toContain("bottom: auto;");
    expect(launcher).toContain("width: 112px;");
    expect(launcher).toContain("height: 48px;");
  });

  it("enquadra a obra selecionada sem alterar as cores semânticas do PDOR", () => {
    const surfaces = lastRule(globalCss, ".obras-list,\n.obras-detail");
    expect(surfaces).toContain("border-radius: var(--radius-container);");

    const active = lastRule(globalCss, ".obras-list-item.active");
    expect(active).toContain("border: 1px solid var(--color-ink);");
    expect(active).toContain("background: #fff;");

    expect(globalCss).not.toContain(".obras-list-item.active::before");

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

  it("achata os itens e controles de Tarefas sem perder as prioridades", () => {
    const item = rule(tarefasCss, ".tarefa-item");
    expect(item).toContain("border: 1px solid var(--color-border);");
    expect(item).toContain("background: var(--color-surface);");

    const fields = rule(
      tarefasCss,
      ".tarefa-form input,\n.tarefa-form textarea",
    );
    expect(fields).toContain("border: 1px solid var(--color-border);");
    expect(fields).toContain("background: var(--color-surface);");

    const priority = rule(tarefasCss, ".tarefa-prio-botao");
    expect(priority).toContain("border: 1px solid var(--color-border);");
    expect(priority).toContain("background: var(--color-surface);");

    expect(lastRule(tarefasCss, ".tarefas-equipe-tab--active")).toContain(
      "box-shadow: inset 0 -3px var(--color-brand-yellow);",
    );
    expect(rule(tarefasCss, ".tarefa-form-enviar")).toContain(
      "box-shadow: none;",
    );
    expect(rule(tarefasCss, ".tarefa-bandeira--1")).toContain(
      "color: #2f6bd8;",
    );
    expect(rule(tarefasCss, ".tarefa-bandeira--2")).toContain(
      "color: #fed203;",
    );
    expect(rule(tarefasCss, ".tarefa-bandeira--3")).toContain(
      "color: #d64545;",
    );
  });

  it("normaliza integrações e Gestão de Obras sem um tema escuro paralelo", () => {
    const tableHeading = rule(integracoesCss, ".integracoes-table th");
    expect(tableHeading).toContain("color: var(--color-ink);");
    expect(tableHeading).toContain("letter-spacing: 0.06em;");

    const reportLabel = rule(integracoesCss, ".integracoes-report dt");
    expect(reportLabel).toContain("color: var(--color-muted);");
    expect(reportLabel).toContain("letter-spacing: 0;");

    const column = rule(gestaoObrasCss, ".gestao-obras-coluna");
    expect(column).toContain("border: 1px solid var(--color-border);");
    expect(column).toContain("border-radius: var(--radius-md);");
    expect(column).toContain("background: var(--color-surface);");

    const active = rule(gestaoObrasCss, ".gestao-obras-item.ativo");
    expect(active).toContain("background: #f7f9f7;");
    expect(active).toContain(
      "box-shadow: inset 3px 0 0 var(--color-brand-yellow);",
    );
    expect(active).toContain("outline: none;");

    const activeFocus = rule(
      gestaoObrasCss,
      ".gestao-obras-item.ativo:focus-visible",
    );
    expect(activeFocus).toContain("outline: 3px solid var(--color-focus);");
    expect(activeFocus).toContain("outline-offset: 2px;");
    expect(gestaoObrasCss).not.toContain("prefers-color-scheme: dark");

    expect(rule(gestaoObrasCss, ".gestao-obras-aviso")).toContain(
      "color: #065f46;",
    );
    expect(rule(gestaoObrasCss, ".gestao-obras-revogar")).toContain(
      "color: #991b1b;",
    );
  });

  it("tokeniza a programação semanal e preserva seus estados semânticos", () => {
    const summary = rule(programacaoCss, ".programacao-import__summary div");
    expect(summary).toContain("border: 1px solid var(--color-border);");
    expect(summary).toContain("border-radius: var(--radius-sm);");
    expect(summary).toContain("background: #f7f9f7;");

    const day = rule(programacaoCss, ".programacao-day");
    expect(day).toContain("border: 1px solid var(--color-border);");
    expect(day).toContain("border-radius: var(--radius-sm);");

    const input = rule(programacaoCss, ".programacao-lines-table input");
    expect(input).toContain("border: 1px solid var(--color-border);");
    expect(input).toContain("border-radius: var(--radius-sm);");

    const selected = rule(programacaoCss, ".programacao-day--selected");
    expect(selected).toContain("border-color: var(--color-brand-teal);");
    expect(selected).toContain("outline: 3px solid rgb(18 78 74 / 16%);");

    expect(rule(programacaoCss, ".programacao-import__warnings span")).toContain(
      "color: #92400e;",
    );
    expect(rule(programacaoCss, ".programacao-day--baixada")).toContain(
      "background: #fff7ed;",
    );
    expect(rule(programacaoCss, ".programacao-match--ok")).toContain(
      "color: #166534;",
    );
    expect(rule(programacaoCss, ".programacao-match--missing")).toContain(
      "color: #b91c1c;",
    );
  });
});
