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

describe("Financeiro revenue-only visual policy", () => {
  it("uses one compact workspace rhythm instead of accumulated section margins", () => {
    const content = rule(
      financeCss,
      ".finance-page > .operational-workspace__content",
    );
    const sections = rule(
      financeCss,
      [
        ".finance-page > .operational-workspace__content > .finance-module-index,",
        ".finance-page > .operational-workspace__content > .finance-scope-bar,",
        ".finance-page > .operational-workspace__content > .finance-revenue-period,",
        ".finance-page > .operational-workspace__content > .finance-revenue-trace,",
        ".finance-page > .operational-workspace__content > .finance-service-catalog,",
        ".finance-page > .operational-workspace__content > .finance-pdor-section",
      ].join("\n"),
    );

    expect(content).toContain("gap: 12px;");
    expect(sections).toContain("margin: 0;");
  });

  it("renders server provenance and exact coverage as a restrained status rail", () => {
    const provenance = rule(financeCss, ".finance-revenue-provenance");

    expect(provenance).toContain("display: grid;");
    expect(provenance).toContain("border-top: 1px solid var(--finance-line);");
    expect(provenance).toContain("border-bottom: 1px solid var(--finance-line);");
    expect(provenance).toContain("background: #eef4f1;");
  });

  it("renders Financeiro PDOR as one quiet shell with a solid inner metric", () => {
    const pdor = rule(financeCss, ".finance-pdor-section .obras-pdor");
    const pdorMain = rule(
      financeCss,
      ".finance-pdor-section .obras-pdor-grid .obras-pdor-main",
    );

    expect(pdor).toContain("margin: 0;");
    expect(pdor).toContain("padding: 18px;");
    expect(pdor).toContain("border: 1px solid var(--finance-line);");
    expect(pdor).toContain("border-radius: var(--radius-container);");
    expect(pdor).toContain("background: var(--surface-glass-fallback);");
    expect(pdor).toContain("box-shadow: none;");
    expect(pdor).not.toContain("2px solid var(--color-ink)");
    expect(pdorMain).toContain("border: 1px solid var(--finance-line);");
    expect(pdorMain).toContain("background: var(--color-surface);");
    expect(pdorMain).not.toContain("2px solid var(--color-ink)");
  });
});
