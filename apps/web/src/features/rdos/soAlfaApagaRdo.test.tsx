// @vitest-environment jsdom

import {
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { RdoLocalList } from "./RdoLocalList";
import type { LocalRdoRecord } from "../../lib/db/db.types";

const mocks = vi.hoisted(() => ({
  listWorksites: vi.fn().mockResolvedValue([]),
}));

vi.mock("./rdoCreationContextRepository", () => ({
  listCachedAuthorizedRdoWorksites: mocks.listWorksites,
}));

vi.mock("../programacoes/ProgramacaoSemanalImport", () => ({
  ProgramacaoSemanalImport: () => null,
}));

vi.mock("../../lib/sync/useSyncStatus", () => ({
  useSyncStatus: () => ({
    snapshot: {
      status: "SYNCED",
      isOnline: true,
      pendingCount: 0,
      syncingCount: 0,
      errorCount: 0,
      conflictCount: 0,
      reviewCount: 0,
      reviewReason: null,
      lastSyncCompletedAt: "2026-08-12T12:00:00.000Z",
      lastSyncError: null,
      isLoading: false,
    },
    refresh: vi.fn(),
  }),
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

/**
 * Apagar RDO é decisão de Alfa — o ciclo inteiro: apagar, recuperar, destruir.
 *
 * <p>O servidor recusa de qualquer forma; estes testes fixam o lado honesto da
 * regra, que é o botão não aparecer para quem não pode usá-lo. A exceção
 * declarada fica registrada junto: o rascunho local que nunca subiu não existe
 * no servidor, não é "apagar RDO", e quem o criou continua podendo descartá-lo.
 */

function rdo(overrides: Partial<LocalRdoRecord> = {}): LocalRdoRecord {
  return {
    id: "00000000-0000-4000-8000-000000000301",
    obraId: "00000000-0000-4000-8000-000000000302",
    programacaoId: null,
    numeroRdo: "RDO-0040",
    dataRdo: "2026-08-12",
    statusRdo: "RASCUNHO",
    canceladoEm: null,
    syncStatus: "SYNCED",
    versaoEntidade: 5,
    payload: {},
    createdAt: "2026-08-12T09:00:00.000Z",
    updatedAt: "2026-08-12T09:00:00.000Z",
    ...overrides,
  } as LocalRdoRecord;
}

function renderizar(
  records: LocalRdoRecord[],
  podeApagarRdo: boolean,
) {
  render(
    <RdoLocalList
      records={records}
      events={[]}
      attachments={[]}
      isLoading={false}
      error=""
      onCreate={vi.fn()}
      onImportRdoFile={vi.fn()}
      isImporting={false}
      onOpen={vi.fn()}
      onCancelRdo={vi.fn()}
      onRestoreRdo={vi.fn()}
      onPurgeRdo={vi.fn()}
      podeApagarRdo={podeApagarRdo}
      onRefresh={vi.fn()}
    />,
  );
}

describe("só Alfa apaga RDO", () => {
  it("não oferece Apagar RDO a quem não é Alfa", () => {
    renderizar([rdo()], false);

    expect(
      screen.queryByRole("button", { name: "Apagar RDO" }),
    ).toBeNull();
  });

  it("não oferece Recuperar nem Apagar definitivamente sobre o cancelado", () => {
    renderizar(
      [rdo({ canceladoEm: "2026-08-12T10:00:00.000Z", statusRdo: "CANCELADA" })],
      false,
    );
    // O cancelado só aparece sob o filtro "Apagado" — o cartão precisa estar
    // na tela para a ausência dos botões significar alguma coisa.
    fireEvent.change(screen.getByRole("combobox", { name: "Status" }), {
      target: { value: "CANCELADA" },
    });

    expect(screen.getByText("RDO-0040")).toBeTruthy();
    expect(
      screen.queryByRole("button", { name: "Recuperar RDO" }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Apagar definitivamente" }),
    ).toBeNull();
  });

  /*
   * A exceção declarada: o rascunho que nunca subiu não existe no servidor.
   * Prendê-lo no aparelho de quem o criou só acumularia lixo sem saída.
   */
  it("mantém o Descartar do rascunho local que nunca subiu", () => {
    renderizar([rdo({ versaoEntidade: null, syncStatus: "LOCAL_ONLY" })], false);

    expect(
      screen.getByRole("button", { name: "Descartar RDO" }),
    ).toBeTruthy();
  });

  it("oferece Apagar RDO a quem é Alfa", () => {
    renderizar([rdo()], true);

    expect(
      screen.getByRole("button", { name: "Apagar RDO" }),
    ).toBeTruthy();
  });

  it("oferece Recuperar e Apagar definitivamente ao Alfa sobre o cancelado", () => {
    renderizar(
      [
        rdo({
          canceladoEm: "2026-08-12T10:00:00.000Z",
          statusRdo: "CANCELADA",
        }),
      ],
      true,
    );
    fireEvent.change(screen.getByRole("combobox", { name: "Status" }), {
      target: { value: "CANCELADA" },
    });

    expect(
      screen.getByRole("button", { name: "Recuperar RDO" }),
    ).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "Apagar definitivamente" }),
    ).toBeTruthy();
  });
});
