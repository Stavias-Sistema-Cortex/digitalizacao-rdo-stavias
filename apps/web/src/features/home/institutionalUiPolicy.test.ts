import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("Cortex 2.1 institutional UI policy", () => {
  const css = readFileSync(
    resolve(process.cwd(), "src/index.css"),
    "utf8",
  );
  const obrasPage = readFileSync(
    resolve(process.cwd(), "src/features/obras/ObrasPage.tsx"),
    "utf8",
  );
  const loginPage = readFileSync(
    resolve(process.cwd(), "src/features/auth/LoginPage.tsx"),
    "utf8",
  );
  const loginCss = readFileSync(
    resolve(process.cwd(), "src/features/auth/LoginPage.css"),
    "utf8",
  );
  const integrationsCss = readFileSync(
    resolve(process.cwd(), "src/features/integracoes/IntegracoesPage.css"),
    "utf8",
  );
  const financeCss = readFileSync(
    resolve(process.cwd(), "src/features/financeiro/FinanceiroPage.css"),
    "utf8",
  );
  const offlineCss = readFileSync(
    resolve(process.cwd(), "src/features/auth/OfflineUnlockPage.css"),
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

  /*
   * As políticas de Memória (memoryHref, FinancePurchasesPanel) e de receita
   * rastreável (FinanceRevenueTracePage) ficaram em cortex-2-1; nada disso
   * existe em develop, então as asserções correspondentes não vieram junto.
   * O launcher local da StavIA em Obras também segue aqui, porque o link
   * "Ver na Memória" que o substituiria depende de memoryHref.
   */

  it("restores the vertical selection bar without a yellow sidebar frame", () => {
    expect(css).toMatch(
      /\.cortex-sidebar\s*\{[^}]*linear-gradient\([^)]*#111312[^)]*var\(--color-brand-teal\)/s,
    );
    expect(css).toContain(".sidebar-nav-item.active::before");
    expect(css).toMatch(
      /\.sidebar-nav-item\.active::before\s*\{[^}]*background:\s*var\(--color-brand-yellow\)/s,
    );
    expect(css).not.toMatch(
      /\.sidebar-nav-item\.active\s*\{[^}]*border[^;}]*var\(--color-brand-yellow\)/s,
    );
  });

  it("replaces thick top accents with complete frames", () => {
    expect(css).not.toMatch(
      /border-top:\s*[2-9]px solid var\(--color-ink\)/,
    );
    expect(integrationsCss).not.toMatch(/border-top:\s*[2-9]px/);
    expect(financeCss).not.toMatch(
      /border-top:\s*[2-9]px solid var\(--finance-ink\)/,
    );
    expect(offlineCss).not.toMatch(/border-top:\s*[2-9]px/);
    expect(css).toMatch(
      /\.home-obra-card\s*\{[^}]*border:\s*2px solid var\(--color-ink\)/s,
    );
  });

  it("uses the approved Obras status and fact treatment", () => {
    expect(obrasPage).toContain('className="obras-status-marker"');
    expect(css).toContain(".obras-status-marker::before");
    expect(css).toMatch(
      /\.obras-facts div\s*\{[^}]*linear-gradient\([^)]*#fff[^)]*#fff3b0/s,
    );
    expect(css).not.toContain(".obras-list-item.active::before");
  });

  it("uses a formal photo-free black-green login", () => {
    expect(loginPage).not.toContain("canteiroBackdrop");
    expect(loginPage).not.toContain("login__backdrop");
    expect(loginPage).toContain("Acesso institucional");
    expect(loginPage).toContain("Entrar no sistema");
    expect(loginCss).toMatch(
      /\.cortex-login\s*\{[^}]*linear-gradient\([^)]*#111312[^)]*#124e4a/s,
    );
    expect(loginCss).toMatch(
      /\.login__card\s*\{[^}]*background:\s*#fff/s,
    );
    expect(loginCss).not.toContain("backdrop-filter");
  });

  it("constrains both institutional login panels on narrow screens", () => {
    expect(loginCss).toMatch(
      /@media \(max-width: 760px\)[\s\S]*?\.login__stage\s*\{[^}]*min-width:\s*0/s,
    );
    expect(loginCss).toMatch(
      /@media \(max-width: 760px\)[\s\S]*?\.login__card\s*\{[^}]*min-width:\s*0/s,
    );
  });
});
