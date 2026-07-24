// @vitest-environment jsdom

import "fake-indexeddb/auto";

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setOfflineSession,
  setSession,
} from "../auth/authSession";
import { ApiTransportError } from "../../lib/api/apiClient";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { FinanceRevenueTracePage } from "./FinanceRevenueTracePage";
import type {
  RevenueTraceEvidence,
  RevenueTraceResponse,
} from "./servicePriceApi";

const mocks = vi.hoisted(() => ({
  fetchRevenueTrace: vi.fn(),
  fetchRevenueTraceEvidence: vi.fn(),
}));

vi.mock("./servicePriceApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./servicePriceApi")>();
  return {
    ...actual,
    fetchRevenueTrace: mocks.fetchRevenueTrace,
    fetchRevenueTraceEvidence: mocks.fetchRevenueTraceEvidence,
  };
});

const OWNER_A = "00000000-0000-4000-8000-000000000201";
const OWNER_B = "00000000-0000-4000-8000-000000000202";
const WORKSITE_A = "00000000-0000-4000-8000-000000000101";
const WORKSITE_B = "00000000-0000-4000-8000-000000000102";
const FROM = "2026-07-01";
const TO = "2026-07-31";
const FETCHED_AT_MS = Date.parse("2026-07-23T15:00:00.000Z");
const HUGE_TOTAL = "9007199254740993.99";

const RESPONSE: RevenueTraceResponse = {
  from: FROM,
  to: TO,
  totalRevenue: HUGE_TOTAL,
  evidenceCount: 1,
  rows: [{
    worksiteId: WORKSITE_A,
    worksiteName: "BR-101",
    rdoId: "00000000-0000-4000-8000-000000000301",
    rdoNumber: "RDO-014",
    executionId: "00000000-0000-4000-8000-000000000401",
    executionDate: "2026-07-22",
    serviceId: "00000000-0000-4000-8000-000000000501",
    serviceCode: "PAV.CBUQ",
    serviceName: "Aplicação de CBUQ",
    priceVersionId: "00000000-0000-4000-8000-000000000601",
    priceVersion: 3,
    quantity: "1.000",
    unit: "T",
    unitPrice: "9007199254740993.9900",
    currency: "BRL",
    revenue: HUGE_TOTAL,
    coverageCode: "ACCEPTED_EXACT",
    revenueEvidenceId: "00000000-0000-4000-8000-000000000701",
    revenueEventId: "00000000-0000-4000-8000-000000000801",
    eventCommitSequence: 812,
    acceptedAt: "2026-07-22T15:00:00Z",
  }],
};

const EVIDENCE: RevenueTraceEvidence = {
  row: RESPONSE.rows[0],
  ontologyLinks: [],
};

let online = true;
let nowMs = FETCHED_AT_MS;
const databases = new Set<string>();

function session(
  ownerId: string,
  obraIds: string[] = [WORKSITE_A, WORKSITE_B],
) {
  return {
    colaboradorId: ownerId,
    nome: "Responsável financeiro",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds,
    expiraEm: "2099-07-23T15:00:00.000Z",
  };
}

async function trackDatabase(ownerId: string, obraIds: string[]) {
  databases.add(
    await databaseNameForScope(ownerId, `BETA:${[...obraIds].sort().join(",")}`),
  );
}

async function cacheOnlineResponse() {
  mocks.fetchRevenueTrace.mockResolvedValueOnce(RESPONSE);
  const rendered = render(
    <FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />,
  );
  expect(
    await screen.findAllByText("R$ 9.007.199.254.740.993,99"),
  ).not.toHaveLength(0);
  await waitFor(() => {
    expect(mocks.fetchRevenueTrace).toHaveBeenCalledTimes(1);
  });
  rendered.unmount();
}

beforeEach(async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  vi.spyOn(window.navigator, "onLine", "get").mockImplementation(() => online);
  vi.spyOn(Date, "now").mockImplementation(() => nowMs);
  online = true;
  nowMs = FETCHED_AT_MS;
  setSession(session(OWNER_A));
  await trackDatabase(OWNER_A, [WORKSITE_A, WORKSITE_B]);
  mocks.fetchRevenueTrace.mockReset();
  mocks.fetchRevenueTraceEvidence.mockReset();
  mocks.fetchRevenueTraceEvidence.mockResolvedValue(EVIDENCE);
});

afterEach(async () => {
  cleanup();
  await closeCortexDb();
  clearSession();
  for (const databaseName of databases) {
    await deleteDB(databaseName);
  }
  databases.clear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("FinanceRevenueTracePage offline confirmation", () => {
  it("expõe fonte, atualização e cobertura exata também na consulta online", async () => {
    mocks.fetchRevenueTrace.mockResolvedValueOnce(RESPONSE);

    render(<FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />);

    expect(
      await screen.findByText(/confirmado pelo servidor.*consulta atual/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/23\/07\/2026.*12:00/)).toBeInTheDocument();
    expect(
      screen.getByText(/cobertura completa.*aceita exata.*1 evidência/i),
    ).toBeInTheDocument();
  });

  it("reutiliza somente o snapshot confirmado do mesmo owner, escopo, obra e filtros", async () => {
    await cacheOnlineResponse();
    online = false;

    render(<FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />);

    expect(
      await screen.findAllByText("R$ 9.007.199.254.740.993,99"),
    ).not.toHaveLength(0);
    expect(screen.getByText(/confirmado pelo servidor.*offline/i))
      .toBeInTheDocument();
    expect(screen.getByText(/23\/07\/2026.*12:00/)).toBeInTheDocument();
    expect(screen.getByText(/cobertura completa.*aceita exata.*1 evidência/i))
      .toBeInTheDocument();
    expect(mocks.fetchRevenueTrace).toHaveBeenCalledTimes(1);
  });

  it("falha fechada quando o snapshot confirmado está vencido", async () => {
    await cacheOnlineResponse();
    nowMs += 24 * 60 * 60 * 1_000 + 1;
    online = false;

    render(<FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />);

    expect(await screen.findByRole("alert"))
      .toHaveTextContent(/expirou|expirado/i);
    expect(screen.queryByText("R$ 9.007.199.254.740.993,99"))
      .not.toBeInTheDocument();
  });

  it("não cruza o snapshot entre owners", async () => {
    await cacheOnlineResponse();
    setOfflineSession(session(OWNER_B));
    await trackDatabase(OWNER_B, [WORKSITE_A, WORKSITE_B]);
    online = false;

    render(<FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />);

    expect(await screen.findByRole("alert"))
      .toHaveTextContent(/nenhum demonstrativo confirmado/i);
    expect(screen.queryByText("R$ 9.007.199.254.740.993,99"))
      .not.toBeInTheDocument();
  });

  it("não cruza o snapshot entre escopos do mesmo owner", async () => {
    await cacheOnlineResponse();
    setOfflineSession(session(OWNER_A, [WORKSITE_A]));
    await trackDatabase(OWNER_A, [WORKSITE_A]);
    online = false;

    render(<FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />);

    expect(await screen.findByRole("alert"))
      .toHaveTextContent(/nenhum demonstrativo confirmado/i);
    expect(screen.queryByText("R$ 9.007.199.254.740.993,99"))
      .not.toBeInTheDocument();
  });

  it("não cruza o snapshot entre obra ou período", async () => {
    await cacheOnlineResponse();
    online = false;

    const worksiteMismatch = render(
      <FinanceRevenueTracePage obraId={WORKSITE_B} de={FROM} ate={TO} />,
    );
    expect(await screen.findByRole("alert"))
      .toHaveTextContent(/nenhum demonstrativo confirmado/i);
    worksiteMismatch.unmount();

    render(
      <FinanceRevenueTracePage
        obraId={WORKSITE_A}
        de={FROM}
        ate="2026-07-30"
      />,
    );
    expect(await screen.findByRole("alert"))
      .toHaveTextContent(/nenhum demonstrativo confirmado/i);
  });

  it("mantém o rastro detalhado indisponível offline sem inventar relações", async () => {
    await cacheOnlineResponse();
    online = false;

    render(<FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />);
    await screen.findAllByText("R$ 9.007.199.254.740.993,99");
    fireEvent.click(screen.getByRole("button", { name: "Ver rastro" }));

    expect(await screen.findByRole("alert"))
      .toHaveTextContent(/rastro detalhado.*indisponível offline/i);
    expect(mocks.fetchRevenueTraceEvidence).not.toHaveBeenCalled();
  });

  it("reutiliza o cache confirmado em falha real de transporte", async () => {
    await cacheOnlineResponse();
    mocks.fetchRevenueTrace.mockRejectedValueOnce(
      new ApiTransportError("rede indisponível", "CONNECTION"),
    );

    render(<FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />);

    expect(
      await screen.findAllByText("R$ 9.007.199.254.740.993,99"),
    ).not.toHaveLength(0);
    expect(screen.getByText(/confirmado pelo servidor.*offline/i))
      .toBeInTheDocument();
  });

  it("falha fechada ao reler um cache adulterado", async () => {
    await cacheOnlineResponse();
    const database = await getCortexDb();
    const [cached] = await database.getAll("finance_revenue_trace_cache");
    expect(cached).toBeDefined();
    await database.put("finance_revenue_trace_cache", {
      ...cached,
      response: {
        ...(cached.response as RevenueTraceResponse),
        totalRevenue: "1.00",
      },
    });
    online = false;

    render(<FinanceRevenueTracePage obraId={WORKSITE_A} de={FROM} ate={TO} />);

    expect(await screen.findByRole("alert"))
      .toHaveTextContent(/nenhum demonstrativo confirmado/i);
    expect(screen.queryByText("R$ 1,00")).not.toBeInTheDocument();
  });

  it("rejeita linha fora do escopo autorizado no consolidado", async () => {
    setSession(session(OWNER_A, [WORKSITE_A]));
    await trackDatabase(OWNER_A, [WORKSITE_A]);
    mocks.fetchRevenueTrace.mockResolvedValueOnce({
      ...RESPONSE,
      rows: [{ ...RESPONSE.rows[0], worksiteId: WORKSITE_B }],
    });

    render(<FinanceRevenueTracePage obraId="" de={FROM} ate={TO} />);

    expect(await screen.findByRole("alert"))
      .toHaveTextContent(/fora do escopo/i);
    expect(screen.queryByText("R$ 9.007.199.254.740.993,99"))
      .not.toBeInTheDocument();
  });
});
