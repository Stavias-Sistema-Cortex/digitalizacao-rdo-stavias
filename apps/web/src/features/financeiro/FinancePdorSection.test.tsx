// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AUTH_SESSION_CHANGED_EVENT } from "../auth/authSession";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import { FinancePdorSection } from "./FinancePdorSection";
import type { PdorRevenueSnapshot } from "./pdorRevenueCacheRepository";

const loadPdorRevenueSnapshot = vi.hoisted(() => vi.fn());

vi.mock("./pdorRevenueCacheRepository", async (importOriginal) => {
  const actual = await importOriginal<
    typeof import("./pdorRevenueCacheRepository")
  >();
  return {
    ...actual,
    loadPdorRevenueSnapshot,
  };
});

vi.mock("../obras/PdorPanel", () => ({
  PdorPanel: ({
    pdor,
    loading,
    error,
  }: {
    pdor: { id: string } | null;
    loading: boolean;
    error: string | null;
  }) => (
    <section data-testid="pdor-panel">
      {loading ? "Carregando PDOR" : pdor?.id ?? error ?? "Sem PDOR"}
    </section>
  ),
}));

const WORKSITE_ID = "00000000-0000-4000-8000-000000000101";
const SNAPSHOT: PdorRevenueSnapshot = {
  pdor: {
    id: "00000000-0000-4000-8000-000000000301",
  } as PdorRevenueSnapshot["pdor"],
  mode: "OFFLINE_CACHE",
  source: "SERVER_CONFIRMED",
  fetchedAt: "2026-07-23T15:00:00.000Z",
  provenance: {
    snapshotId: "00000000-0000-4000-8000-000000000301",
    worksiteId: WORKSITE_ID,
    referenceDate: "2026-07-22",
    temporalWindow: {
      inicioProgramacao: "2026-01-05",
      fimProgramacao: "2026-07-22",
      dataReferencia: "2026-07-22",
      janelaEquipamentosDias: 30,
      serieHistoricaSemanal: true,
    },
    evidenceHighWaterMark: 812,
    coverageCode: "COMPLETE_ACCEPTED_EXACT",
    evidenceCount: 1,
    algorithmVersion: "PDOR-REVENUE-1",
    executedAtUtc: "2026-07-22T14:59:58Z",
  },
};

beforeEach(() => {
  loadPdorRevenueSnapshot.mockReset();
  loadPdorRevenueSnapshot.mockResolvedValue(SNAPSHOT);
});

afterEach(() => {
  cleanup();
});

describe("FinancePdorSection", () => {
  it("expõe a proveniência do snapshot confirmado disponível offline", async () => {
    render(
      <FinancePdorSection
        obraId={WORKSITE_ID}
      />,
    );

    expect(await screen.findByTestId("pdor-panel")).toHaveTextContent(
      "00000000-0000-4000-8000-000000000301",
    );
    expect(screen.getByText(/confirmada pelo servidor.*disponível offline/i))
      .toBeInTheDocument();
    expect(screen.getByText(/cobertura completa.*1 evidência/i))
      .toBeInTheDocument();
    expect(screen.getByText(/commit ontológico 812/i)).toBeInTheDocument();
    expect(screen.getByText(/janela.*05\/01\/2026.*22\/07\/2026/i))
      .toBeInTheDocument();
    expect(loadPdorRevenueSnapshot).toHaveBeenCalledWith({
      obraId: WORKSITE_ID,
    });
  });

  it("recarrega automaticamente após sync, reconexão e mudança de sessão", async () => {
    render(
      <FinancePdorSection
        obraId={WORKSITE_ID}
      />,
    );
    await waitFor(() => {
      expect(loadPdorRevenueSnapshot).toHaveBeenCalledTimes(1);
    });

    window.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));
    await waitFor(() => {
      expect(loadPdorRevenueSnapshot).toHaveBeenCalledTimes(2);
    });

    window.dispatchEvent(new Event("online"));
    await waitFor(() => {
      expect(loadPdorRevenueSnapshot).toHaveBeenCalledTimes(3);
    });

    window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT));
    await waitFor(() => {
      expect(loadPdorRevenueSnapshot).toHaveBeenCalledTimes(4);
    });
  });

  it("falha fechada sem preservar um snapshot anterior após erro", async () => {
    const { rerender } = render(
      <FinancePdorSection
        obraId={WORKSITE_ID}
        refreshVersion={0}
      />,
    );
    expect(await screen.findByTestId("pdor-panel")).toHaveTextContent(
      "00000000-0000-4000-8000-000000000301",
    );

    loadPdorRevenueSnapshot.mockRejectedValueOnce(
      new Error("Nenhuma previsão PDOR confirmada está disponível offline."),
    );
    rerender(
      <FinancePdorSection
        obraId={WORKSITE_ID}
        refreshVersion={1}
      />,
    );

    expect(await screen.findByTestId("pdor-panel")).toHaveTextContent(
      /nenhuma previsão PDOR confirmada/i,
    );
    expect(
      screen.queryByText(/confirmada pelo servidor.*disponível offline/i),
    ).not.toBeInTheDocument();
  });
});
