import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

function source(relativePath: string): string {
  return readFileSync(resolve(process.cwd(), relativePath), "utf8");
}

describe("Home institutional command layout", () => {
  const homePage = source("src/features/home/HomePage.tsx");
  const homeOverview = source("src/features/home/HomeOverview.tsx");
  const memorySummary = source("src/features/home/MemorySummaryCard.tsx");
  const memoryLedger = source(
    "src/features/home/memory/MemoryLedger.tsx",
  );
  const memoryHook = source(
    "src/features/home/memory/useMemoryLedger.ts",
  );
  const memoryCss = source(
    "src/features/home/memory/MemoryLedger.css",
  );

  it("uses the shared header and makes freshness and sync truth explicit", () => {
    expect(homePage).toContain("InstitutionalPageHeader");
    expect(homePage).toContain('eyebrow="Centro operacional"');
    expect(homeOverview).toContain("useSyncStatus");
    expect(homeOverview).toContain("SyncStateStrip");
    expect(homeOverview).toContain("Atualização local");
    expect(homeOverview).toContain("Última sincronização");
  });

  it("places operational exceptions before the worksite and secondary links", () => {
    const exceptionsIndex = homeOverview.indexOf(
      'className="home-exception-register"',
    );
    const worksiteIndex = homeOverview.indexOf("<ObraFocusCard");
    const summariesIndex = homeOverview.indexOf(
      'className="home-command-summaries"',
    );
    const secondaryIndex = homeOverview.indexOf(
      'className="home-secondary-links"',
    );
    const externalLinksIndex = homeOverview.indexOf("<MaisStaviasCard");

    expect(exceptionsIndex).toBeGreaterThan(-1);
    expect(worksiteIndex).toBeGreaterThan(exceptionsIndex);
    expect(summariesIndex).toBeGreaterThan(worksiteIndex);
    expect(secondaryIndex).toBeGreaterThan(summariesIndex);
    expect(externalLinksIndex).toBeGreaterThan(secondaryIndex);
    expect(homeOverview).not.toContain('className="home-cards-grid"');
    expect(memorySummary).toContain("home-command-summary");
  });

  it("keeps the complete ontology history in a dense technical Memory ledger", () => {
    expect(memoryLedger).toContain('aria-label="Filtros da Memória"');
    expect(memoryLedger).toContain("<table");
    for (const column of [
      "ID do evento",
      "Horário",
      "Ator",
      "Dispositivo",
      "Entidade",
      "Ação",
      "Origem",
      "Resultado",
    ]) {
      expect(memoryLedger).toContain(column);
    }
  });

  it("renders review-needed records from stored conflicts instead of a placeholder", () => {
    expect(memoryLedger).toContain("Revisão necessária");
    expect(memoryLedger).toContain("memory-review-register");
    expect(memoryLedger).toContain("reviewRecords");
    expect(memoryHook).toContain("listOutboxMutations");
    expect(memoryHook).toContain("memoryConflictReviewRecords");
    expect(memoryHook).toContain("reviewRecords");
  });

  it("keeps device-wide sync totals distinct from the focused worksite", () => {
    expect(homeOverview).toContain("Conflitos no dispositivo");
    expect(homeOverview).toContain("Falhas no dispositivo");
    expect(homeOverview).toContain("Na fila do dispositivo");
    expect(homeOverview).toContain("nesta obra");
  });

  it("does not report an empty review queue before the stored outbox arrives", () => {
    expect(memoryHook).toContain("isReviewLoading");
    expect(memoryHook).toContain("isInitialLoading || isOutboxLoading");
    expect(memoryLedger).toContain("isReviewLoading");
    expect(memoryLedger).toContain("Consultando a fila local de revisão");
  });

  it("keeps the review register unavailable when its stored outbox cannot be read", () => {
    expect(memoryHook).toContain("reviewError");
    expect(memoryLedger).toContain("reviewError");
    expect(memoryLedger).toContain(
      "Não foi possível verificar a fila local de itens que exigem revisão.",
    );
  });

  it("expands and locates the event named by a shared trace link", () => {
    expect(memoryLedger).toContain('search.get("event")');
    expect(memoryLedger).toContain("setExpandedIds");
    expect(memoryLedger).toContain("scrollIntoView");
  });

  it("keeps Home surfaces framed and non-decorative", () => {
    expect(memoryCss).not.toMatch(/border-radius:\s*999px/);
    expect(memoryCss).not.toMatch(/border-left:\s*[2-9]px/);
    expect(memoryCss).not.toContain("backdrop-filter");
    expect(memoryCss).not.toContain("box-shadow");
  });
});
