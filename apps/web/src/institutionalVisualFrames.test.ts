import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

function source(path: string): string {
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

const globalCss = source("./index.css");
const financeCss = source("./features/financeiro/FinanceiroPage.css");
const teamsCss = source("./features/equipes/EquipesPage.css");
const tasksCss = source("./features/tarefas/TarefasPage.css");
const scheduleCss = source("./features/programacoes/ProgramacaoSemanalImport.css");
const rdoCss = source("./features/rdos/RdoWorkspacePage.css");

describe("molduras institucionais do Córtex", () => {
  it("substitui faixas isoladas por uma moldura financeira integral", () => {
    const scope = rule(financeCss, ".finance-scope-bar");
    const scopeAccent = rule(financeCss, ".finance-scope-bar::before");

    expect(scope).toContain("border: 2px solid var(--finance-ink);");
    expect(scope).toContain("border-radius: var(--radius-control);");
    expect(scopeAccent).toContain("content: none;");
  });

  it("preserva destaque de PDOR e tarefas sem meio-highlight ou pílulas", () => {
    const risk = lastRule(globalCss, ".obras-pdor-risk");
    const insufficient = lastRule(globalCss, ".obras-pdor-insufficient");
    const mainPdor = lastRule(globalCss, ".obras-pdor-grid .obras-pdor-main");
    const activeWorksite = lastRule(tasksCss, ".tarefas-obra-tabs .chip--active");

    expect(risk).toContain("border-radius: 3px;");
    expect(risk).toContain("border: 1px solid");
    expect(insufficient).toContain("border: 2px solid #c59f18;");
    expect(insufficient).not.toContain("border-left:");
    expect(mainPdor).toContain("border: 2px solid var(--color-ink);");
    expect(mainPdor).not.toContain("border-left:");
    expect(activeWorksite).toContain("background: var(--color-ink);");
    expect(activeWorksite).not.toContain("linear-gradient");
  });

  it("achata estados de equipe e superfícies de sobreposição", () => {
    const status = rule(teamsCss, ".teams-status");
    const drawer = rule(teamsCss, ".teams-member-drawer");
    const modal = rule(teamsCss, ".teams-modal");

    expect(status).toContain("border-radius: 3px;");
    expect(status).toContain("border: 1px solid currentColor;");
    expect(drawer).toContain("border-left: 2px solid var(--color-ink);");
    expect(drawer).toContain("box-shadow: none;");
    expect(modal).toContain("border: 2px solid var(--color-ink);");
    expect(modal).toContain("box-shadow: none;");
  });

  it("mantém a programação e as ações de RDO como registros quadrados", () => {
    const warning = lastRule(scheduleCss, ".programacao-import__warnings span");
    const match = lastRule(scheduleCss, ".programacao-match");
    const primary = lastRule(rdoCss, ".rdo-dashboard .primary-button,\n.rdo-dashboard .button.primary,\n.rdo-create-workspace .primary-button,\n.rdo-create-workspace .button.primary");

    expect(warning).toContain("border-radius: 3px;");
    expect(match).toContain("border: 1px solid currentColor;");
    expect(match).toContain("border-radius: 3px;");
    expect(primary).toContain("background: var(--color-ink);");
    expect(primary).toContain("border: 1px solid var(--color-ink);");
  });

  it("retira a decoração amarela do título e formaliza a gaveta de perfil", () => {
    const heading = rule(globalCss, ".section-heading h2::before");
    const drawer = rule(globalCss, ".profile-drawer");
    const icon = rule(globalCss, ".profile-drawer .icon-button");

    expect(heading).toContain("content: none;");
    expect(drawer).toContain("border-left: 2px solid var(--color-ink);");
    expect(drawer).toContain("box-shadow: none;");
    expect(icon).toContain("border-radius: 3px;");
  });
});
