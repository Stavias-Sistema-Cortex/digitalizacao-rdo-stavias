import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

function source(path: string): string {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

function rule(css: string, selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`Regra CSS ausente: ${selector}`);
  const end = css.indexOf("\n}", start);
  if (end < 0) throw new Error(`Regra CSS sem fechamento: ${selector}`);
  return css.slice(start, end + 2);
}

const financeCss = source("./FinanceiroPage.css");

/**
 * Política visual do Financeiro.
 *
 * A versão anterior desta política travava a paleta paralela `--finance-*`,
 * que divergia da marca — o teal do Financeiro não era o teal do app. A
 * política atual é a inversa e é o que este teste defende: NENHUMA paleta
 * própria; toda cor vem dos tokens de `index.css`. Quem reintroduzir um
 * `--finance-teal` reabre a porta pela qual a aba descolou do resto.
 */
describe("Financeiro revenue-only visual policy", () => {
  it("does not declare a parallel palette", () => {
    expect(financeCss).not.toContain("--finance-teal");
    expect(financeCss).not.toContain("--finance-ink");
    expect(financeCss).not.toContain("--finance-yellow");
    expect(financeCss).not.toContain("--finance-muted");
  });

  it("uses one compact workspace rhythm instead of accumulated section margins", () => {
    const content = rule(
      financeCss,
      ".finance-page > .operational-workspace__content",
    );
    const sections = rule(
      financeCss,
      [
        ".finance-module-index,",
        ".finance-scope-bar,",
        ".finance-revenue-period,",
        ".finance-revenue-trace,",
        ".finance-service-catalog,",
        ".finance-pdor-section",
      ].join("\n"),
    );

    expect(content).toContain("gap: 12px;");
    expect(sections).toContain("margin: 0;");
  });

  it("renders server provenance and exact coverage as a restrained status rail", () => {
    const provenance = rule(financeCss, ".finance-revenue-provenance");

    expect(provenance).toContain("display: grid;");
    expect(provenance).toContain("border-top: 1px solid var(--color-border);");
    expect(provenance).toContain(
      "border-bottom: 1px solid var(--color-border);",
    );
    expect(provenance).toContain("background: var(--color-canvas);");
  });

  it("renders Financeiro PDOR as one quiet shell with a solid inner metric", () => {
    const pdor = rule(financeCss, ".finance-pdor-section .obras-pdor");
    const pdorMain = rule(
      financeCss,
      ".finance-pdor-section .obras-pdor-grid .obras-pdor-main",
    );

    expect(pdor).toContain("margin: 0;");
    expect(pdor).toContain("padding: 18px;");
    expect(pdor).toContain("border: 1px solid var(--color-border);");
    expect(pdor).toContain("border-radius: var(--radius-container);");
    expect(pdor).toContain("background: var(--surface-glass-fallback);");
    expect(pdor).toContain("box-shadow: none;");
    expect(pdor).not.toContain("2px solid var(--color-ink)");
    expect(pdorMain).toContain("border: 1px solid var(--color-border);");
    expect(pdorMain).toContain("background: var(--color-surface);");
    expect(pdorMain).not.toContain("2px solid var(--color-ink)");
  });
});
