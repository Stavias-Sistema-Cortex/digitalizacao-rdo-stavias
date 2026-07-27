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
const headerCss = readCss("./components/header/CortexPageHeader.css");
const syncCss = readCss("./components/SyncStatusBanner.css");
const tarefasCss = readCss("./features/tarefas/TarefasPage.css");
const integracoesCss = readCss("./features/integracoes/IntegracoesPage.css");
const gestaoObrasCss = readCss("./features/obras/gestao/gestaoObras.css");
const programacaoCss = readCss(
  "./features/programacoes/ProgramacaoSemanalImport.css",
);
const mensagensCss = readCss("./features/mensagens/MensagensPage.css");
const operationalWorkspaceCss = readCss(
  "./components/workspace/OperationalWorkspace.css",
);
const rdoWorkspaceCss = readCss("./features/rdos/RdoWorkspacePage.css");
const deviceSecurityCss = readCss("./features/auth/DeviceSecurityPage.css");
const financeiroCss = readCss("./features/financeiro/FinanceiroPage.css");
const equipesCss = readCss("./features/equipes/EquipesPage.css");

describe("polimento visual da plataforma autenticada", () => {
  it("centraliza a paleta de campo e a escala moderna de superfícies", () => {
    expect(rule(globalCss, ":root")).toContain("--color-ink: #111312;");
    expect(rule(globalCss, ":root")).toContain(
      "--color-text: var(--color-ink);",
    );
    expect(rule(globalCss, ":root")).toContain("--color-brand-teal: #124e4a;");
    expect(rule(globalCss, ":root")).toContain("--color-brand-yellow: #f2c800;");
    expect(rule(globalCss, ":root")).toContain("--color-canvas: #edf2ef;");
    expect(rule(globalCss, ":root")).toContain(
      "--color-border: rgb(18 58 55 / 14%);",
    );
    expect(rule(globalCss, ":root")).toContain(
      "--surface-glass: rgb(255 255 255 / 78%);",
    );
    expect(rule(globalCss, ":root")).toContain(
      "--surface-glass-fallback: #f8fbf9;",
    );
    expect(rule(globalCss, ":root")).toContain("--glass-shadow:");
    expect(rule(globalCss, ":root")).toContain("--radius-control: 8px;");
    expect(rule(globalCss, ":root")).toContain("--radius-container: 14px;");
    expect(rule(globalCss, ":root")).toContain(
      "--radius-sm: var(--radius-control);",
    );
    expect(rule(globalCss, ":root")).toContain(
      "--radius-lg: var(--radius-container);",
    );
  });

  it("aplica vidro líquido somente nas superfícies externas com fallback opaco", () => {
    const institutionalFrame = rule(globalCss, ".institutional-frame");
    const documentSurface = rule(rdoWorkspaceCss, ".rdo-document-surface");
    const integrationCard = rule(
      integracoesCss,
      ".integracoes-table-card,\n.integracoes-report",
    );
    const teamsFrame = rule(
      equipesCss,
      ".teams-workspace .teams-page",
    );
    const managementColumn = rule(gestaoObrasCss, ".gestao-obras-coluna");
    const workspaceRail = rule(
      operationalWorkspaceCss,
      ".workspace-status-rail",
    );
    const financeScope = rule(financeiroCss, ".finance-scope-bar");

    for (const surface of [
      institutionalFrame,
      documentSurface,
      integrationCard,
      teamsFrame,
      managementColumn,
      workspaceRail,
      financeScope,
    ]) {
      expect(surface).toContain("background: var(--surface-glass-fallback);");
      expect(surface).toContain("box-shadow: var(--glass-shadow);");
    }
    expect(teamsFrame).not.toContain("overflow: hidden;");

    expect(globalCss).toContain(
      "@supports ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px)))",
    );
    expect(globalCss).toContain("backdrop-filter: blur(14px) saturate(112%);");
    expect(globalCss).toContain(
      "@media (prefers-reduced-transparency: reduce)",
    );
    expect(globalCss).not.toMatch(
      /(?:@supports|prefers-reduced-transparency)[\s\S]*?\.offline-unlock__card,/,
    );
  });

  it("usa uma única superfície contínua para sidebar e cabeçalhos", () => {
    const sidebar = lastRule(globalCss, ".cortex-sidebar");
    const shell = rule(globalCss, ".cortex-shell");
    const header = rule(headerCss, ".cortex-page-header");
    const compatibilityHeader = rule(
      headerCss,
      ".cortex-page-header.workspace-header,\n" +
        ".cortex-page-header.institutional-page-header,\n" +
        ".cortex-page-header.mensagens-header",
    );

    expect(shell).toContain("--cortex-shell-chrome-surface:");
    expect(shell).toContain("background: var(--cortex-shell-chrome-surface);");
    expect(shell).toContain("background-attachment: fixed;");
    expect(shell).toContain("background-size: 100vw 100dvh;");
    expect(shell).toContain("background-position: 0 0;");
    expect(sidebar).toContain("background: transparent;");
    expect(header).toContain("background: var(--cortex-shell-chrome-surface);");
    expect(header).toContain("background-attachment: fixed;");
    expect(header).toContain("background-size: 100vw 100dvh;");
    expect(header).toContain("background-position: 0 0;");
    expect(compatibilityHeader).toContain(
      "background: var(--cortex-shell-chrome-surface);",
    );
    expect(compatibilityHeader).toContain("background-attachment: fixed;");
    expect(compatibilityHeader).toContain("background-size: 100vw 100dvh;");
    expect(compatibilityHeader).toContain("background-position: 0 0;");
    expect(headerCss).not.toContain("--cortex-shell-chrome-surface:");
  });

  it("projeta o chrome sobre os canvases legados opacos", () => {
    expect(rule(operationalWorkspaceCss, ".operational-workspace")).toContain(
      "background: var(--color-canvas, #f3f5f2);",
    );
    expect(
      rule(rdoWorkspaceCss, ".rdo-dashboard,\n.rdo-create-workspace"),
    ).toContain("background: var(--color-canvas);");
    expect(rule(mensagensCss, ".mensagens-page")).toContain(
      "background: var(--color-canvas);",
    );
    expect(rule(deviceSecurityCss, ".device-security-page")).toContain(
      "background: var(--color-canvas);",
    );
    expect(rule(financeiroCss, ".finance-page")).toContain(
      "background: var(--finance-canvas);",
    );
  });

  it("mantém a alavanca de recolher visível e sinalizada", () => {
    const sidebar = lastRule(globalCss, ".cortex-sidebar");
    const shell = rule(globalCss, ".cortex-shell");
    const collapsedShell = rule(globalCss, ".cortex-shell--collapsed");
    const toggle = lastRule(globalCss, ".sidebar-toggle");
    const toggleFocus = rule(globalCss, ".sidebar-toggle:focus-visible");

    expect(sidebar).toContain("z-index: 1;");
    expect(shell).toContain("position: relative;");
    expect(shell).toContain(
      "--cortex-sidebar-edge: var(--sidebar-width, 248px);",
    );
    expect(collapsedShell).toContain("--cortex-sidebar-edge: 84px;");
    expect(toggle).toContain("width: 44px;");
    expect(toggle).toContain("height: 48px;");
    expect(toggle).toContain("left: calc(var(--cortex-sidebar-edge) - 22px);");
    expect(toggle).toContain("right: auto;");
    expect(toggle).toContain("background: var(--color-brand-yellow);");
    expect(toggle).toContain("color: #111312;");
    expect(toggleFocus).toContain(
      "outline: 3px solid var(--color-brand-yellow);",
    );
    expect(toggleFocus).toContain("outline-offset: 3px;");
  });

  it("mantém métricas operacionais discretas", () => {
    expect(rule(globalCss, ".metric-card")).toContain(
      "background: var(--color-surface);",
    );
  });

  it("assina métricas RDO com uma faixa de pista sem moldura pesada", () => {
    const metricCard = rule(globalCss, ".metric-card");
    const metricStripe = rule(globalCss, ".metric-card::before");
    expect(metricCard).toContain("border: 1px solid var(--color-border);");
    expect(metricCard).toContain("border-radius: var(--radius-container);");
    expect(metricCard).toContain("overflow: hidden;");
    expect(metricStripe).toContain('content: "";');
    expect(metricStripe).toContain("width: 48px;");
    expect(metricStripe).toContain("height: 3px;");
    expect(metricStripe).toContain("background: var(--color-brand-yellow);");
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

  it("mantém contraste legível nas notas pequenas da Home", () => {
    expect(rule(globalCss, ".home-card-muted")).toContain(
      "color: var(--color-muted);",
    );
    expect(rule(globalCss, ".home-updated-at")).toContain(
      "color: var(--color-muted);",
    );
  });

  it("alinha à esquerda e contém a hierarquia operacional dos RDOs", () => {
    const filterLabel = rule(globalCss, ".rdo-filter-grid label");
    expect(filterLabel).toContain("letter-spacing: 0;");
    expect(filterLabel).toContain("text-transform: none;");

    const metric = rule(globalCss, ".metric-card");
    expect(metric).toContain("min-height: 82px;");
    expect(metric).toContain("padding: 14px 16px;");
    expect(metric).toContain("border-radius: var(--radius-container);");
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
    const avatar = rule(globalCss, ".avatar-button");
    expect(avatar).toContain("width: 40px;");
    expect(avatar).toContain("height: 40px;");
    expect(avatar).toContain("border: 1px solid rgb(255 255 255 / 24%);");
    expect(avatar).toContain("border-radius: var(--radius-control);");
    expect(avatar).toContain("background: #101112;");
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

  it("keeps mobile chrome in normal document flow without a detached control row", () => {
    const narrowCss = globalCss.slice(
      globalCss.lastIndexOf("@media (max-width: 620px)"),
    );
    expect(globalCss).not.toContain(".floating-controls");
    expect(
      rule(
        narrowCss,
        ".home-dashboard,\n  .rdo-dashboard,\n  .obras-page",
      ),
    ).toContain("padding-top: 0;");

    const tarefasNarrowCss = tarefasCss.slice(
      tarefasCss.lastIndexOf("@media (max-width: 620px)"),
    );
    expect(rule(tarefasNarrowCss, ".tarefas-page")).toContain(
      "padding: 0;",
    );

  });

  it("keeps Messages in document flow without viewport magic heights", () => {
    const workspace = rule(mensagensCss, ".mensagens-workspace");
    expect(workspace).toContain("min-height: 32rem;");
    expect(workspace).toContain("flex: 1 1 auto;");
    expect(workspace).not.toContain("\n  height:");
    expect(workspace).not.toContain("calc(100vh");
  });

  it("marca a obra selecionada sem alterar as cores semânticas do PDOR", () => {
    const surfaces = rule(globalCss, ".obras-list,\n.obras-detail");
    expect(surfaces).toContain("border: 1px solid var(--color-border);");
    expect(surfaces).toContain("border-radius: var(--radius-lg);");
    expect(surfaces).toContain("background: var(--surface-glass-fallback);");
    expect(surfaces).toContain("box-shadow: var(--glass-shadow);");

    const nestedPdor = rule(globalCss, ".obras-pdor");
    expect(nestedPdor).toContain("border: 0;");
    expect(nestedPdor).toContain(
      "border-top: 1px solid var(--color-border);",
    );
    expect(nestedPdor).toContain("background: transparent;");

    const pdorMain = lastRule(
      globalCss,
      ".obras-pdor-grid .obras-pdor-main",
    );
    expect(pdorMain).toContain("border: 1px solid var(--color-border);");
    expect(pdorMain).toContain(
      "border-top: 3px solid var(--color-brand-yellow);",
    );

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
    expect(globalCss).toContain(
      ".home-dashboard {\n    padding: 0;",
    );
    expect(globalCss).toContain(
      [
        "  .home-uf-filter,",
        "  .home-uf-filter select,",
        "  .home-obra-selector,",
        "  .home-obra-selector select {",
        "    width: 100%;",
        "    max-width: none;",
      ].join("\n"),
    );
    expect(globalCss).toContain(
      ".home-obra-chart {\n    min-width: 0;\n    width: 100%;",
    );
    expect(globalCss).toContain(
      [
        "  .rdo-command-actions,",
        "  .rdo-command-actions button,",
        "  .action-bar .button {",
        "    width: 100%;",
      ].join("\n"),
    );

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

    expect(rule(tarefasCss, ".tarefas-equipe-tab--active")).toContain(
      "background: var(--color-brand-yellow);",
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
    expect(tableHeading).toContain("color: var(--color-muted);");
    expect(tableHeading).toContain("letter-spacing: 0;");

    const reportLabel = rule(integracoesCss, ".integracoes-report dt");
    expect(reportLabel).toContain("color: var(--color-muted);");
    expect(reportLabel).toContain("letter-spacing: 0;");

    const column = rule(gestaoObrasCss, ".gestao-obras-coluna");
    expect(column).toContain("border: 1px solid var(--color-border);");
    expect(column).toContain("border-radius: var(--radius-md);");
    expect(column).toContain("background: var(--surface-glass-fallback);");
    expect(column).toContain("box-shadow: var(--glass-shadow);");

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
