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
const cortex3Css = globalCss.slice(
  globalCss.lastIndexOf("/* Camada final Cortex 3"),
);
const rdoCss = source("./features/rdos/RdoWorkspacePage.css");
const financeCss = source("./features/financeiro/FinanceiroPage.css");
const tasksCss = source("./features/tarefas/TarefasPage.css");

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

  it("keeps the authenticated chrome compact and lets content use the workspace", () => {
    const sidebar = rule(globalCss, ".cortex-sidebar");
    const controls = rule(cortex3Css, ".floating-controls");
    const home = rule(cortex3Css, ".home-dashboard");
    const worksites = rule(cortex3Css, ".obras-page");

    expect(sidebar).toContain("#111312 0%");
    expect(sidebar).toContain("var(--color-brand-teal) 100%");
    expect(controls).toContain("position: relative;");
    expect(controls).not.toContain("position: fixed;");
    expect(home).toContain("padding: 32px");
    expect(worksites).toContain("max-width: none;");
  });

  it("applies the same restrained controls to RDO, Financeiro and Tarefas", () => {
    const rdoPrimary = lastRule(
      rdoCss,
      ".rdo-create-workspace .button.primary",
    );
    const financePrimary = lastRule(
      financeCss,
      ".finance-scope-bar nav button.is-active,\n.finance-primary-action",
    );
    const financeAccent = lastRule(financeCss, ".finance-scope-bar::before");
    const taskSubmit = lastRule(tasksCss, ".tarefa-form-enviar");
    const taskGeometry = lastRule(
      tasksCss,
      ".tarefas-nova-equipe input,\n.tarefas-nova-equipe button,\n.tarefa-excluir,\n.tarefa-form input,\n.tarefa-form textarea,\n.tarefa-prio-botao,\n.tarefa-sugestoes,\n.tarefa-sugestoes button,\n.tarefa-form-enviar",
    );

    expect(rdoPrimary).toContain("background: var(--color-ink);");
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
