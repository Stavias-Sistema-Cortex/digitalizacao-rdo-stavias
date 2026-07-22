import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { InstitutionalPageHeader } from "./InstitutionalPageHeader";
import { InstitutionalStatus } from "./InstitutionalStatus";
import { SyncStateStrip } from "./SyncStateStrip";
import { TraceReference } from "./TraceReference";

describe("institutional interface primitives", () => {
  it("renders a labelled page header with a single page heading", () => {
    const markup = renderToStaticMarkup(
      <InstitutionalPageHeader eyebrow="Ontologia operacional" title="Obras" />,
    );

    expect(markup).toContain("<header");
    expect(markup).toContain("Ontologia operacional");
    expect(markup).toContain("<h1>Obras</h1>");
  });

  it("links a trace reference directly to its memory event without a timeline", () => {
    const markup = renderToStaticMarkup(
      <TraceReference entityId="obra-42" eventId="event-123" />,
    );

    expect(markup).toContain('href="/home?tab=memory&amp;event=event-123"');
    expect(markup).not.toMatch(/timeline/i);
  });

  it("exposes an institutional state through the status role", () => {
    const markup = renderToStaticMarkup(<InstitutionalStatus state="SYNCING" />);

    expect(markup).toContain('role="status"');
    expect(markup).toContain('data-state="SYNCING"');
  });

  it("renders synchronization facts with a machine-readable last-sync time", () => {
    const markup = renderToStaticMarkup(
      <SyncStateStrip
        snapshot={{
          status: "SYNCED",
          isOnline: true,
          pendingCount: 0,
          syncingCount: 0,
          errorCount: 0,
          conflictCount: 0,
          reviewCount: 0,
          reviewReason: null,
          lastSyncCompletedAt: "2026-07-17T12:30:00.000Z",
          lastSyncError: null,
          isLoading: false,
        }}
      />,
    );

    expect(markup).toContain("<dl");
    expect(markup).toContain("<time");
    expect(markup).toContain('dateTime="2026-07-17T12:30:00.000Z"');
    expect(markup).toContain('data-state="SYNCED"');
  });

  it("keeps sync status and counters unknown while local state is loading", () => {
    const markup = renderToStaticMarkup(
      <SyncStateStrip
        snapshot={{
          status: "SYNCED",
          isOnline: true,
          pendingCount: 0,
          syncingCount: 0,
          errorCount: 0,
          conflictCount: 0,
          reviewCount: 0,
          reviewReason: null,
          lastSyncCompletedAt: null,
          lastSyncError: null,
          isLoading: true,
        }}
      />,
    );

    expect(markup).toContain('data-sync-status="CHECKING"');
    expect(markup).toContain("Verificando estado local");
    expect(markup).toContain('aria-label="Ainda verificando"');
    expect(markup).not.toContain('data-state="SYNCED"');
    expect(markup).not.toContain(">0</dd>");
  });

  it("shows a manual sync failure instead of a stale synced snapshot", () => {
    const manualFailureProps = {
      snapshot: {
        status: "SYNCED" as const,
        isOnline: true,
        pendingCount: 0,
        syncingCount: 0,
        errorCount: 0,
        conflictCount: 0,
        reviewCount: 0,
        reviewReason: null,
        lastSyncCompletedAt: null,
        lastSyncError: null,
        isLoading: false,
      },
      presentationError: "A sincronização manual expirou.",
    };
    const markup = renderToStaticMarkup(
      <SyncStateStrip {...manualFailureProps} />,
    );

    expect(markup).toContain('data-sync-status="ERROR"');
    expect(markup).toContain("Falha na sincronização");
    expect(markup).toContain("A sincronização manual expirou.");
    expect(markup).not.toContain('data-state="SYNCED"');
  });

  it("reports sync errors without mislabelling them as rejections", () => {
    const markup = renderToStaticMarkup(
      <SyncStateStrip
        snapshot={{
          status: "ERROR",
          isOnline: true,
          pendingCount: 0,
          syncingCount: 0,
          errorCount: 2,
          conflictCount: 0,
          reviewCount: 0,
          reviewReason: null,
          lastSyncCompletedAt: null,
          lastSyncError: "Servidor indisponível.",
          isLoading: false,
        }}
      />,
    );

    expect(markup).not.toContain('data-state="REJECTED"');
    expect(markup).not.toContain("Rejeitado");
    expect(markup).toContain('data-sync-status="ERROR"');
    expect(markup).toContain('data-sync-error-count="2"');
    expect(markup).toContain('role="status"');
    expect(markup).toContain("Falha na sincronização");
    expect(markup).toContain("Servidor indisponível.");
  });

  it("shows rejected mutations as a review requirement, not a transient sync failure", () => {
    const markup = renderToStaticMarkup(
      <SyncStateStrip
        snapshot={{
          status: "REVIEW",
          isOnline: true,
          pendingCount: 0,
          syncingCount: 0,
          errorCount: 0,
          conflictCount: 0,
          reviewCount: 1,
          reviewReason: "Envelope canonico v13 incompleto.",
          lastSyncCompletedAt: "2026-07-20T12:30:00.000Z",
          lastSyncError: null,
          isLoading: false,
        }}
      />,
    );

    expect(markup).toContain('data-sync-status="REVIEW"');
    expect(markup).toContain('data-sync-review-count="1"');
    expect(markup).toContain("Revisão necessária");
    expect(markup).toContain("Envelope canonico v13 incompleto.");
    expect(markup).not.toContain("Falha na sincronização");
  });

});
