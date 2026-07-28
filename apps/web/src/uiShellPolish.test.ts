import { existsSync, readFileSync } from "node:fs";

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
const shellCss = readCss("./components/shell/CortexShell.css");
const headerCss = readCss("./components/header/CortexPageHeader.css");
const syncCss = readCss("./components/SyncStatusBanner.css");
const operationalWorkspaceCss = readCss(
  "./components/workspace/OperationalWorkspace.css",
);
const loginCss = readCss("./features/auth/LoginPage.css");
const mensagensCss = readCss("./features/mensagens/MensagensPage.css");
const financeiroCss = readCss("./features/financeiro/FinanceiroPage.css");
const equipesCss = readCss("./features/equipes/EquipesPage.css");
const workforceCss = readCss("./features/rdos/RdoWorkforceEditor.css");
const operationalMapCss = readCss("./features/obras/map/OperationalMap.css");
const rdoCreationCss = readCss("./features/rdos/RdoCreationDialog.css");
const novaObraCss = readCss("./features/obras/gestao/NovaObraForm.css");
const tarefasCss = readCss("./features/tarefas/TarefasPage.css");
const integracoesCss = readCss("./features/integracoes/IntegracoesPage.css");
const taskTypographyCss = [
  globalCss,
  shellCss,
  headerCss,
  syncCss,
  operationalWorkspaceCss,
  loginCss,
  mensagensCss,
  financeiroCss,
  equipesCss,
  workforceCss,
  operationalMapCss,
  rdoCreationCss,
  novaObraCss,
  tarefasCss,
  integracoesCss,
].join("\n");

describe("shell e superfícies operacionais", () => {
  it("usa a paleta de campo opaca e somente fontes realmente embarcadas", () => {
    const root = rule(globalCss, ":root");
    expect(root).toContain("--color-ink: #18211f;");
    expect(root).toContain("--color-brand-teal: #0c2623;");
    expect(root).toContain("--color-brand-yellow: #f2c300;");
    expect(root).toContain("--color-surface: #f7f9f7;");
    expect(root).toContain("--color-border: #d4deda;");
    expect(root).toContain("--color-danger: #a43a3a;");
    expect(root).toContain("--surface-glass-fallback: var(--color-surface);");
    expect(root).toContain("--glass-shadow: none;");
    expect(globalCss).not.toContain("/fonts/poppins-700.ttf");
    expect(globalCss).not.toContain("/fonts/poppins-800.ttf");
    expect(
      existsSync(
        new URL("../public/fonts/poppins-700.ttf", import.meta.url),
      ),
    ).toBe(false);
    expect(
      existsSync(
        new URL("../public/fonts/poppins-800.ttf", import.meta.url),
      ),
    ).toBe(false);
  });

  it("limita a tipografia operacional a 400, 500 e 600", () => {
    const weights = [...taskTypographyCss.matchAll(/font-weight:\s*(\d+)/g)]
      .map((match) => Number(match[1]));
    expect(weights.length).toBeGreaterThan(0);
    expect([...new Set(weights)].sort()).toEqual([400, 500, 600]);
    expect(rule(globalCss, "strong,\nb")).toContain("font-weight: 600;");
  });

  it("isola o shell e oferece uma alavanca escura de 36px", () => {
    const shell = rule(shellCss, ".cortex-shell");
    const sidebar = rule(shellCss, ".cortex-sidebar");
    const toggle = rule(shellCss, ".sidebar-toggle");
    const toggleFocus = rule(shellCss, ".sidebar-toggle:focus-visible");

    expect(shell).toContain("--cortex-sidebar-edge:");
    expect(sidebar).toContain("background: transparent;");
    expect(toggle).toContain("width: 36px;");
    expect(toggle).toContain("height: 36px;");
    expect(toggle).toContain("left: calc(var(--cortex-sidebar-edge) - 18px);");
    expect(toggle).toContain("border: 1px solid #35514d;");
    expect(toggle).toContain("background: #0c2623;");
    expect(toggle).toContain("color: var(--color-brand-yellow);");
    expect(toggle).toContain("box-shadow: none;");
    expect(toggleFocus).toContain(
      "outline: 3px solid var(--color-brand-yellow);",
    );
    expect(globalCss).not.toMatch(/\.(?:cortex-shell|cortex-sidebar|sidebar-)/);
  });

  it("reduz o cabeçalho a uma linha amarela de 1px", () => {
    const header = rule(headerCss, ".cortex-page-header");
    expect(header).toContain(
      "border-block-end: 1px solid var(--color-brand-yellow, #f2c300);",
    );
    expect(header).toContain(
      "padding: clamp(18px, 2.2vw, 28px) clamp(18px, 3vw, 40px) 16px;",
    );
    expect(rule(headerCss, ".cortex-page-header h1")).toContain(
      "font-weight: 600;",
    );
    expect(rule(headerCss, ".cortex-page-header__eyebrow")).toContain(
      "font-weight: 500;",
    );
  });

  it("quebra o estado operacional sem truncar label ou detalhe", () => {
    const state = rule(
      operationalWorkspaceCss,
      ".workspace-status-rail__state",
    );
    const copy = rule(
      operationalWorkspaceCss,
      ".workspace-status-rail__state > strong,\n" +
        ".workspace-status-rail__state > span:last-child",
    );

    expect(state).toContain("flex-wrap: wrap;");
    expect(copy).toContain("min-inline-size: 0;");
    expect(copy).toContain("overflow-wrap: anywhere;");
    expect(copy).toContain("white-space: normal;");
    expect(copy).not.toContain("overflow: hidden;");
    expect(copy).not.toContain("text-overflow: ellipsis;");
  });

  it("remove blur e sombras das superfícies sem achatar overlays", () => {
    expect(globalCss).not.toContain("backdrop-filter:");
    expect(rule(operationalWorkspaceCss, ".workspace-status-rail")).toContain(
      "box-shadow: none;",
    );
    expect(rule(operationalWorkspaceCss, ".workspace-status-rail")).toContain(
      "padding: 12px;",
    );
    expect(rule(financeiroCss, ".finance-scope-bar")).toContain(
      "box-shadow: none;",
    );
    expect(rule(equipesCss, ".teams-workspace .teams-page")).toContain(
      "box-shadow: none;",
    );
    expect(rule(integracoesCss, ".integracoes-table-card,\n.integracoes-report"))
      .toContain("box-shadow: none;");
    expect(rule(syncCss, ".sync-chip__popover")).toContain(
      "box-shadow: var(--shadow-overlay);",
    );
    expect(rule(rdoCreationCss, ".rdo-creation-dialog")).toContain(
      "box-shadow: var(--shadow-overlay);",
    );
    expect(rule(novaObraCss, ".nova-obra-dialog")).toContain(
      "box-shadow: var(--shadow-overlay);",
    );
  });
});
