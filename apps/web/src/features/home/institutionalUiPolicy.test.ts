import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("Cortex 2.1 institutional UI policy", () => {
  const css = readFileSync(
    resolve(process.cwd(), "src/index.css"),
    "utf8",
  );
  const financeApi = readFileSync(
    resolve(process.cwd(), "src/features/financeiro/financeiroApi.ts"),
    "utf8",
  );
  const financePurchases = readFileSync(
    resolve(process.cwd(), "src/features/financeiro/FinancePurchasesPanel.tsx"),
    "utf8",
  );
  const financePage = readFileSync(
    resolve(process.cwd(), "src/features/financeiro/FinanceiroPage.tsx"),
    "utf8",
  );

  it("defines the institutional palette and geometry", () => {
    expect(css).toContain("--color-ink: #111312");
    expect(css).toContain("--radius-control: 4px");
    expect(css).toContain("--radius-container: 6px");
  });

  it("provides restrained motion without changing StavIA geometry", () => {
    expect(css).toContain("@media (prefers-reduced-motion: reduce)");
    expect(css).not.toMatch(
      /\.stavia-launcher\s*\{[^}]*border-radius:\s*4px/s,
    );
  });

  it("keeps ontology event listings exclusive to Home > Memória", () => {
    expect(financeApi).not.toContain("/ontology/timeline");
    expect(financePurchases).not.toContain("buscarAuditoriaFinanceira");
    expect(financePurchases).toContain("memoryHref");
  });

  it("keeps Financeiro focused on traceable operational revenue", () => {
    expect(financePage).toContain('label: "Rastreio de receita"');
    expect(financePage).toContain("FinanceRevenueTracePage");
    expect(financePage).not.toContain('label: "Compras"');
    expect(financePage).not.toContain('label: "Notas fiscais"');
    expect(financePage).not.toContain('label: "Pagamentos e cobranças"');
  });

  it("frames complete sidebar buttons over a black-green gradient", () => {
    expect(css).toMatch(
      /\.cortex-sidebar\s*\{[^}]*linear-gradient\([^)]*#111312[^)]*var\(--color-brand-teal\)/s,
    );
    expect(css).toMatch(
      /\.sidebar-nav-item\.active\s*\{[^}]*border:\s*1px solid var\(--color-brand-yellow\)/s,
    );
    expect(css).not.toContain(".sidebar-nav-item.active::before");
  });
});
