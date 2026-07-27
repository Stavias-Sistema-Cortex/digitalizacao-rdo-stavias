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
    isExporting: false,
    currentDeviceId: null,
    viewMode: "CONSOLIDATED",
    exportNotice: null,
    reviewNotice: null,
    reconcilingMutationId: null,
    error: null,
    setFilters: vi.fn(),
    clearFilters: vi.fn(),
    loadMore: vi.fn(),
    refresh: vi.fn(),
    setViewMode: vi.fn(),
    exportLedger: vi.fn(),
    reconcileReview: vi.fn(),
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
    expect(html).toContain("Consolidado");
    expect(html).toContain("Este dispositivo");
    expect(html).toContain("ID interno do ator");
    expect(html).toContain("Exportar");
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
                actorId: "actor-1",
                deviceId: "device-1",
                origin: "SYNC",
                result: "SYNCED",
              },
              syncStatus: "UPDATED",
              sourceKind: "SERVER",
              occurredAt: "2026-07-27T14:18:00.000Z",
              eventType: "RDO_EDITADO",
              source: "SYNC_PUSH",
              principalName: "RDO 17",
              worksiteName: "BR-262",
              rdoNumber: "17",
              serviceName: "Compactação",
              errorCategory: null,
              responsibleUserName: "João Operador",
              trace: {
                clientMutationId: "mutation-1",
                correlationId: "correlation-1",
                causationId: null,
                entityVersion: 4,
              },
              review: null,
            },
          ],
        })}
        obras={[]}
      />,
    );

    expect(html).toContain("Atualizado");
    expect(html).toContain("Commit 71");
    expect(html).toContain("event-1");
    expect(html).toContain("Responsável");
    expect(html).toContain("João Operador");
    expect(html).toContain("27/07/2026, 11:18");
  });

  it("renders compact graph checkpoint, target and lag in the coverage strip", () => {
    const html = renderToStaticMarkup(
      <MemoryLedgerView
        ledger={model({
          metadata: {
            userId: "u",
            scopeHash: "s",
            highWaterMark: 50,
            authorizedEventCount: 50,
            cachedEventCount: 50,
            oldestCommitSequence: 1,
            newestCommitSequence: 50,
            coverageMode: "FULL_HISTORY",
            serverCoverageComplete: true,
            graph: {
              checkpointCommitSequence: 47,
              targetCommitSequence: 50,
              lagEventCount: 3,
              fresh: false,
              lastSafeError: null,
            },
            complete: true,
            cachedAt: "2026-07-22T10:00:00.000Z",
          },
        })}
        obras={[]}
      />,
    );

    expect(html).toContain("Grafo ontológico");
    expect(html).toContain("47 / 50 · 3 pendentes");
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
              actorId: "actor-1",
              deviceId: null,
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
            responsibleUserName: null,
            trace: {
              clientMutationId: "mutation-pii",
              correlationId: null,
              causationId: null,
              entityVersion: 1,
            },
            review: null,
          }],
        })}
        obras={[]}
      />,
    );

    expect(html).toContain("Identidade protegida");
    expect(html).not.toContain("undefined");
  });

  it.each([
    [
      "CACHE",
      "O cache local não pôde ser lido.",
      "Os registros armazenados podem estar indisponíveis neste dispositivo.",
    ],
    [
      "SERVER",
      "O servidor não confirmou a atualização.",
      "Os registros já armazenados continuam disponíveis.",
    ],
  ] as const)("renders literal %s error copy", (source, title, detail) => {
    const html = renderToStaticMarkup(
      <MemoryLedgerView
        ledger={model({
          error: { source, message: "Falha controlada." },
        })}
        obras={[]}
      />,
    );

    expect(html).toContain(title);
    expect(html).toContain(detail);
  });

  it("renders truthful terminal review versions and withholds an unsafe action", () => {
    const html = renderToStaticMarkup(
      <MemoryLedgerView
        ledger={model({
          totalMatches: 1,
          items: [{
            key: "u:s:event-conflict",
            userId: "u",
            scopeHash: "s",
            eventId: "event-conflict",
            commitSequence: null,
            normalizedText: "rdo conflito",
            structuralKeys: {
              eventType: "RDO_EDITADO",
              entityType: "RDO",
              entityId: "rdo-1",
              worksiteId: "obra-1",
              rdoId: "rdo-1",
              actorId: "actor-1",
              deviceId: "device-1",
              origin: "OFFLINE",
              result: "CONFLICT",
            },
            syncStatus: "CONFLICT",
            sourceKind: "LOCAL",
            occurredAt: "2026-07-22T10:00:00.000Z",
            eventType: "RDO_EDITADO",
            source: "OFFLINE",
            principalName: "RDO 17",
            worksiteName: "BR-262",
            rdoNumber: "17",
            serviceName: null,
            errorCategory: "VERSION_CONFLICT",
            responsibleUserName: "João Operador",
            trace: {
              clientMutationId: "mutation-1",
              correlationId: "correlation-1",
              causationId: null,
              entityVersion: 3,
            },
            review: {
              status: "CONFLICT",
              clientMutationId: "mutation-1",
              baseVersion: 3,
              eventVersion: 3,
              remoteVersion: 4,
              localStateAvailable: true,
              remoteStateAvailable: false,
              changedFields: ["titulo"],
              conflictFields: ["titulo"],
              canReconcile: false,
              unavailableReason: "REMOTE_SNAPSHOT_UNAVAILABLE",
            },
          }],
        })}
        obras={[]}
      />,
    );

    expect(html).toContain("Revisão necessária");
    expect(html).toContain("Versão-base");
    expect(html).toContain(">3<");
    expect(html).toContain("Versão remota");
    expect(html).toContain(">4<");
    expect(html).toContain("Estado remoto indisponível");
    expect(html).not.toContain("Conciliar alterações");
  });
});
