import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { MemoryLedgerView } from "./MemoryLedger";
import type { MemoryLedgerViewModel } from "./useMemoryLedger";

function model(overrides: Partial<MemoryLedgerViewModel> = {}): MemoryLedgerViewModel {
  return {
    items: [],
    totalMatches: 0,
    hasMoreLocal: false,
    filters: {},
    coverage: {
      code: "PARTIAL",
      label: "Parcial",
      detail: "O cache ainda não cobre todo o histórico autorizado.",
    },
    metadata: null,
    isLoading: false,
    isRefreshing: false,
    error: null,
    setFilters: vi.fn(),
    clearFilters: vi.fn(),
    loadMore: vi.fn(),
    refresh: vi.fn(),
    ...overrides,
  };
}

describe("Memory ledger accessibility and honest states", () => {
  it("exposes a labelled search, structural filters and a live coverage state", () => {
    const html = renderToStaticMarkup(
      <MemoryLedgerView ledger={model()} obras={[]} />,
    );

    expect(html).toContain('type="search"');
    expect(html).toContain('aria-label="Pesquisar em toda a Memória armazenada"');
    expect(html).toContain('aria-label="Filtros estruturais da Memória"');
    expect(html).toContain('role="status"');
    expect(html).toContain("Parcial");
  });

  it("describes active filters when the result is empty", () => {
    const html = renderToStaticMarkup(
      <MemoryLedgerView
        ledger={model({ filters: { q: "compactação", eventType: "RDO_EDITADO" } })}
        obras={[]}
      />,
    );

    expect(html).toContain("Nenhum evento corresponde aos filtros ativos");
    expect(html).not.toContain("Evento de exemplo");
  });

  it("renders literal durable status and exact event/commit evidence", () => {
    const html = renderToStaticMarkup(
      <MemoryLedgerView
        ledger={model({
          totalMatches: 1,
          items: [
            {
              key: "u:s:event-1",
              userId: "u",
              scopeHash: "s",
              eventId: "event-1",
              commitSequence: 71,
              normalizedText: "compactacao",
              structuralKeys: {
                eventType: "RDO_EDITADO",
                entityType: "RDO",
                entityId: "rdo-1",
                worksiteId: "obra-1",
                rdoId: "rdo-1",
                origin: "SYNC",
                result: "SYNCED",
              },
              syncStatus: "UPDATED",
              sourceKind: "SERVER",
              occurredAt: "2026-07-22T10:00:00.000Z",
              eventType: "RDO_EDITADO",
              source: "SYNC_PUSH",
              principalName: "RDO 17",
              worksiteName: "BR-262",
              rdoNumber: "17",
              serviceName: "Compactação",
              errorCategory: null,
            },
          ],
        })}
        obras={[]}
      />,
    );

    expect(html).toContain("Atualizado");
    expect(html).toContain("Commit 71");
    expect(html).toContain("event-1");
  });

  it("renders a redacted PII principal as protected identity", () => {
    const html = renderToStaticMarkup(
      <MemoryLedgerView
        ledger={model({
          totalMatches: 1,
          items: [{
            key: "u:s:event-pii",
            userId: "u",
            scopeHash: "s",
            eventId: "event-pii",
            commitSequence: 72,
            normalizedText: "colaborador atualizado",
            structuralKeys: {
              eventType: "COLABORADOR_ATUALIZADO",
              entityType: "COLABORADOR",
              entityId: null,
              worksiteId: null,
              rdoId: null,
              origin: "SYNC",
              result: "SYNCED",
            },
            syncStatus: "UPDATED",
            sourceKind: "SERVER",
            occurredAt: "2026-07-22T10:00:00.000Z",
            eventType: "COLABORADOR_ATUALIZADO",
            source: "SYNC_PUSH",
            principalName: null,
            worksiteName: null,
            rdoNumber: null,
            serviceName: null,
            errorCategory: null,
          }],
        })}
        obras={[]}
      />,
    );

    expect(html).toContain("Identidade protegida");
    expect(html).not.toContain("undefined");
  });
});
