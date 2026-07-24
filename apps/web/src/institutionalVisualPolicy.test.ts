import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

function source(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

function lastRule(css: string, selector: string): string {
  const start = css.lastIndexOf(`${selector} {`);
  if (start < 0) throw new Error(`Regra CSS ausente: ${selector}`);
  const end = css.indexOf("\n}", start);
  if (end < 0) throw new Error(`Regra CSS sem fechamento: ${selector}`);
  return css.slice(start, end + 2);
}

function rule(css: string, selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`Regra CSS ausente: ${selector}`);
  const end = css.indexOf("\n}", start);
  if (end < 0) throw new Error(`Regra CSS sem fechamento: ${selector}`);
  return css.slice(start, end + 2);
}

const globalCss = source("./index.css");
const headerCss = source("./components/header/CortexPageHeader.css");
const workspaceCss = source("./components/workspace/OperationalWorkspace.css");
const rdoCss = source("./features/rdos/RdoWorkspacePage.css");
const rdoDialogCss = source("./features/rdos/RdoCreationDialog.css");
const financeCss = source("./features/financeiro/FinanceiroPage.css");
const mensagensCss = source("./features/mensagens/MensagensPage.css");
const tasksCss = source("./features/tarefas/TarefasPage.css");
const deviceSecuritySource = source(
  "./features/auth/DeviceSecurityPage.tsx",
);

describe("Cortex 3 institutional visual policy", () => {
  it("uses the black structural action language instead of legacy yellow pills", () => {
    const primary = lastRule(
      globalCss,
      ".primary-button,\n.button.primary",
    );
    const primaryGeometry = lastRule(
      globalCss,
      ".primary-button,\n.secondary-button",
    );

    expect(primary).toContain("background: var(--color-ink);");
    expect(primary).toContain("color: #fff;");
    expect(primary).not.toMatch(/brand-yellow|#fed203|999px/);
    expect(primaryGeometry).toContain("border-radius: var(--radius-control);");
  });

  it("contains desktop chrome in the viewport and scrolls only the content column", () => {
    const shell = rule(globalCss, ".cortex-shell");
    const sidebar = rule(globalCss, ".cortex-sidebar");
    const content = rule(globalCss, ".cortex-shell-content");

    expect(shell).toContain("height: 100dvh;");
    expect(shell).toContain("min-height: 0;");
    expect(shell).toContain("overflow: hidden;");
    expect(sidebar).toContain("height: 100%;");
    expect(sidebar).toContain("overflow-y: auto;");
    expect(sidebar).not.toContain("position: sticky;");
    expect(sidebar).toContain("#111312 0%");
    expect(sidebar).toContain("var(--color-brand-teal) 100%");
    expect(content).toContain("height: 100%;");
    expect(content).toContain("overflow-y: auto;");
    expect(globalCss).not.toContain(".floating-controls");
  });

  it("returns shell, content, and sidebar to normal flow below 900px", () => {
    const mobileCss = globalCss.slice(globalCss.indexOf("@media (max-width: 900px)"));
    const shell = rule(
      mobileCss,
      "  .cortex-shell,\n  .cortex-shell--collapsed",
    );
    const content = rule(mobileCss, "  .cortex-shell-content");
    const sidebar = rule(mobileCss, "  .cortex-sidebar,\n  .rdo-side-panel");

    expect(shell).toContain("height: auto;");
    expect(shell).toContain("overflow: visible;");
    expect(content).toContain("height: auto;");
    expect(content).toContain("overflow-y: visible;");
    expect(sidebar).toContain("position: static;");
    expect(sidebar).toContain("height: auto;");
    expect(sidebar).toContain("overflow-y: visible;");
  });

  it("keeps the selected sidebar item on the dark rail with a yellow locator", () => {
    const active = lastRule(globalCss, ".sidebar-nav-item.active");
    const locator = lastRule(globalCss, ".sidebar-nav-item.active::before");
    const icon = lastRule(globalCss, ".sidebar-nav-item.active img");

    expect(active).toContain("background: rgb(255 255 255 / 10%);");
    expect(active).toContain("color: #eef7ef;");
    expect(active).not.toContain("background: #fff;");
    expect(locator).toContain('content: "";');
    expect(locator).toContain("background: var(--color-brand-yellow);");
    expect(icon).toContain("filter: grayscale(1) brightness(0) invert(1);");
  });

  it("lets the operational ribbon reach every content edge without a gray top band", () => {
    const workspace = rule(workspaceCss, ".operational-workspace");
    const workspaceContent = rule(
      workspaceCss,
      ".operational-workspace__content",
    );
    const messagesPage = rule(mensagensCss, ".mensagens-page");
    const rdoPages = rule(
      rdoCss,
      ".rdo-dashboard,\n.rdo-create-workspace",
    );

    expect(workspace).toContain("padding: 0;");
    expect(workspace).toContain("gap: 0;");
    expect(workspaceContent).toContain("padding:");
    expect(messagesPage).toContain("width: 100%;");
    expect(messagesPage).toContain("padding: 0;");
    expect(rdoPages).toContain("width: 100%;");
    expect(rdoPages).toContain("padding: 0;");
    expect(deviceSecuritySource).toContain("<CortexPageHeader");
    expect(deviceSecuritySource).not.toContain('<header className="topbar">');
    expect(globalCss).not.toContain(".floating-controls");
  });

  it("uses neutral hairlines for RDO document surfaces and reserves ink for actions", () => {
    const documentSurface = rule(rdoCss, ".rdo-document-surface");
    const documentStatus = rule(rdoCss, ".rdo-document-status");
    const formCard = rule(rdoCss, ".rdo-create-workspace .form-card");
    const action = lastRule(
      rdoCss,
      ".rdo-create-workspace .button.primary",
    );
    const dialog = rule(rdoDialogCss, ".rdo-creation-dialog");
    const provenance = rule(rdoDialogCss, ".rdo-provenance-rail");

    for (const surface of [documentSurface, documentStatus, formCard]) {
      expect(surface).toContain("border: 1px solid var(--color-border);");
      expect(surface).not.toContain("2px solid var(--color-ink)");
    }
    expect(dialog).toContain("border: 1px solid var(--color-border);");
    expect(provenance).not.toContain("box-shadow:");
    expect(action).toContain("background: var(--color-ink);");
  });

  it("presents Financeiro as a quiet operational workspace", () => {
    const financeHeader = rule(headerCss, ".cortex-page-header");
    const financeTitle = rule(headerCss, ".cortex-page-header h1");
    const financeNavigation = rule(
      financeCss,
      ".finance-module-index",
    );
    const financeNavigationActive = rule(
      financeCss,
      ".finance-module-index button.is-active",
    );
    const financeNavigationLabel = rule(
      financeCss,
      ".finance-module-index__label",
    );
    const financeScopeLabel = rule(
      financeCss,
      ".finance-scope-bar__identity span,\n.finance-scope-bar__selection label",
    );
    const financeHeaderDatum = rule(
      financeCss,
      ".finance-command-context dt",
    );
    const financeContent = rule(
      financeCss,
      ".finance-page > .operational-workspace__content",
    );

    expect(financeHeader).toContain("linear-gradient(");
    expect(financeHeader).toContain("#124e4a 100%");
    expect(financeTitle).toContain("color: #fff;");
    expect(financeCss).not.toContain(".finance-page > .workspace-header");
    expect(financeNavigation).toContain("background: transparent;");
    expect(financeNavigation).toContain("overflow-x: auto;");
    expect(financeNavigationActive).toContain("background: #e4efea;");
    expect(financeNavigationActive).toContain(
      "box-shadow: inset 0 -2px var(--finance-yellow);",
    );
    expect(financeNavigationLabel).toContain("color: var(--finance-muted);");
    expect(financeScopeLabel).toContain("color: var(--finance-muted);");
    expect(financeHeaderDatum).toContain("color: #cbd8d2;");
    expect(financeContent).toContain("padding:");
    expect(financeCss).not.toContain("border-top: 2px solid var(--finance-ink);");
  });

  it("keeps Financeiro units as fluid white cards without a gray grid remainder", () => {
    const grid = rule(financeCss, ".finance-unit-grid");
    const card = rule(financeCss, ".finance-unit-grid > button");
    const selectedSpine = rule(
      financeCss,
      ".finance-unit-grid > button.is-selected::before",
    );

    expect(grid).toContain("grid-template-columns: repeat(auto-fit, minmax(min(100%, 270px), 1fr));");
    expect(grid).toContain("gap: 12px;");
    expect(grid).toContain("background: transparent;");
    expect(card).toContain("border: 1px solid var(--finance-line);");
    expect(card).toContain("background: #fff;");
    expect(card).not.toContain("box-shadow:");
    expect(selectedSpine).toContain("var(--finance-yellow) 0 22%");
    expect(selectedSpine).toContain("var(--finance-teal) 22% 100%");
  });

  it("applies the same restrained controls to RDO, Financeiro and Tarefas", () => {
    const rdoPrimary = lastRule(
      rdoCss,
      ".rdo-create-workspace .button.primary",
    );
    const financeScopeActive = lastRule(
      financeCss,
      ".finance-scope-bar nav button.is-active",
    );
    const financePrimary = rule(
      financeCss,
      ".finance-primary-action",
    );
    const financeAccent = lastRule(
      financeCss,
      ".finance-scope-bar::before",
    );
    const taskSubmit = lastRule(tasksCss, ".tarefa-form-enviar");
    const taskGeometry = lastRule(
      tasksCss,
      ".tarefas-nova-equipe input,\n.tarefas-nova-equipe button,\n.tarefa-excluir,\n.tarefa-form input,\n.tarefa-form textarea,\n.tarefa-prio-botao,\n.tarefa-sugestoes,\n.tarefa-sugestoes button,\n.tarefa-form-enviar",
    );

    expect(rdoPrimary).toContain("background: var(--color-ink);");
    expect(financeScopeActive).toContain("color: var(--finance-teal-strong);");
    expect(financeScopeActive).toContain("background: #fff;");
    expect(financePrimary).toContain("background: var(--finance-ink);");
    expect(financeAccent).toContain("content: none;");
    expect(taskSubmit).toContain("background: var(--color-ink);");
    expect(taskGeometry).toContain("border-radius: var(--radius-control);");
  });

  it("removes decorative yellow section markers from authenticated forms", () => {
    expect(lastRule(globalCss, ".section-heading h2::before"))
      .toContain("content: none;");
    expect(lastRule(rdoCss, ".rdo-create-workspace .section-heading h2::before"))
      .toContain("content: none;");
  });
});
