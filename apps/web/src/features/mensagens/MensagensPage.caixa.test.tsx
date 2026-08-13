// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";

import { MensagensPage } from "./MensagensPage";

/*
 * Arrumar a caixa é gesto de leitor. A tela precisa provar duas coisas: que o
 * gesto vai ao endereço pessoal — nunca a uma exclusão — e que a gaveta lê as
 * arquivadas sem gravá-las como retrato do aparelho, porque gravar apagaria
 * daqui justamente a lista principal.
 */
const mocks = vi.hoisted(() => ({
  arquivar: vi.fn(),
  limpar: vi.fn(),
  listar: vi.fn(),
  refreshList: vi.fn(),
  storeServer: vi.fn(),
  listLocal: vi.fn(),
  listPreviews: vi.fn(),
  listObras: vi.fn(),
  listMessages: vi.fn(),
  gravar: vi.fn(),
  listarPref: vi.fn(),
}));

vi.mock("../auth/authSession", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../auth/authSession")>()),
  getSession: () => ({
    colaboradorId: "00000000-0000-4000-8000-000000000001",
    nome: "Quem arruma",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [] as string[],
    expiraEm: "2099-01-01T00:00:00.000Z",
  }),
  hasOnlineSession: () => true,
  isAlfa: () => true,
  requireDataScope: () => ({
    ownerId: "00000000-0000-4000-8000-000000000001",
    scopeMaterial: "ALFA:GLOBAL",
  }),
}));

vi.mock("./mensagensApi", () => ({
  arquivarConversaApi: mocks.arquivar,
  limparConversaApi: mocks.limpar,
  listConversationsApi: mocks.listar,
}));

vi.mock("./mensagensHydration", () => ({
  refreshConversationList: mocks.refreshList,
  refreshConversationHistory: vi.fn(),
}));

vi.mock("./mensagensRepository", () => ({
  listLocalConversations: mocks.listLocal,
  listLocalConversationPreviews: mocks.listPreviews,
  listLocalMessages: mocks.listMessages,
  storeServerConversations: mocks.storeServer,
  gravarPreferenciaDaConversa: mocks.gravar,
  listarPreferenciasDeConversa: mocks.listarPref,
  MESSAGES_CHANGED_EVENT: "cortex:mensagens-alteradas",
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  listObrasLocais: mocks.listObras,
}));

vi.mock("../../lib/sync/syncEngine", () => ({ syncNow: vi.fn() }));

vi.mock("../../lib/sync/useSyncStatus", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/sync/useSyncStatus")>()),
  useSyncStatus: () => ({
    snapshot: {
      status: "SYNCED",
      isOnline: true,
      pendingCount: 0,
      syncingCount: 0,
      errorCount: 0,
      conflictCount: 0,
      reviewCount: 0,
      insistindoCount: 0,
      reenviaveisCount: 0,
      travadasCount: 0,
      travaMotivo: null,
      reviewReason: null,
      lastSyncCompletedAt: null,
      lastSyncError: null,
      isLoading: false,
    },
    refresh: vi.fn(),
  }),
}));

const CONVERSA = {
  id: "conversa-1",
  tipo: "GRUPO",
  titulo: "Frente A",
  obraId: null,
  equipeId: null,
  status: "ATIVA",
  criadaEm: "2026-08-01T10:00:00",
  atualizadaEm: "2026-08-01T10:00:00",
  versao: 1,
  participantes: [],
};

describe("arrumar a própria caixa de mensagens", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // jsdom não tem ResizeObserver, e a tela usa um para saber se o quadro
    // cabe as três colunas.
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    mocks.listLocal.mockResolvedValue([CONVERSA]);
    mocks.listPreviews.mockResolvedValue({});
    mocks.listObras.mockResolvedValue([]);
    mocks.listMessages.mockResolvedValue([]);
    mocks.refreshList.mockResolvedValue(undefined);
    mocks.arquivar.mockResolvedValue(undefined);
    mocks.limpar.mockResolvedValue(undefined);
    mocks.listar.mockResolvedValue([]);
    mocks.gravar.mockResolvedValue(undefined);
    mocks.listarPref.mockResolvedValue(new Map());
  });

  afterEach(() => {
    cleanup();
  });

  function abrir() {
    render(
      <MemoryRouter>
        <MensagensPage />
      </MemoryRouter>,
    );
  }

  /*
   * A gaveta sai do próprio aparelho: quem arquivou sem rede precisa conseguir
   * desarquivar sem rede. Buscá-la no servidor trancaria a saída justamente
   * para quem está em campo.
   */
  it("a gaveta das arquivadas sai do aparelho, não do servidor", async () => {
    abrir();

    fireEvent.click(await screen.findByRole("button", { name: "Arquivadas" }));

    expect(
      await screen.findByText("Você não arquivou nenhuma conversa."),
    ).toBeInTheDocument();
    expect(mocks.listar).not.toHaveBeenCalled();
    expect(mocks.storeServer).not.toHaveBeenCalled();
  });

  it("devolver à lista desfaz o arquivamento aqui e avisa o servidor", async () => {
    mocks.listarPref.mockResolvedValue(
      new Map([
        [
          "conversa-1",
          {
            conversaId: "conversa-1",
            arquivadoEm: "2026-08-13T10:00:00.000Z",
            limpoAte: null,
            pendente: false,
          },
        ],
      ]),
    );
    abrir();

    fireEvent.click(await screen.findByRole("button", { name: "Arquivadas" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "Devolver à lista" }),
    );

    await waitFor(() =>
      expect(mocks.gravar).toHaveBeenCalledWith(
        "conversa-1",
        expect.objectContaining({ arquivadoEm: null }),
      ),
    );
    expect(mocks.arquivar).toHaveBeenCalledWith("conversa-1", false);
  });

  /*
   * O gesto que quebrou em produção: limpar gravava só no servidor, e a tela
   * lê o cache do aparelho — o botão parecia morto, e só arquivar e
   * desarquivar (que descarta o cache) fazia o histórico sumir.
   */
  it("limpar grava a cortina no aparelho antes de qualquer rede", async () => {
    abrir();

    fireEvent.click(
      await screen.findByRole("button", { name: "Limpar conversa" }),
    );

    await waitFor(() =>
      expect(mocks.gravar).toHaveBeenCalledWith(
        "conversa-1",
        expect.objectContaining({
          limpoAte: expect.any(String),
          pendente: true,
        }),
      ),
    );
  });

  /* Sem rede o gesto continua valendo aqui; desfazê-lo seria pior. */
  it("sem rede a arrumação fica guardada e a tela diz isso", async () => {
    mocks.limpar.mockRejectedValue(new Error("sem conexão"));
    abrir();

    fireEvent.click(
      await screen.findByRole("button", { name: "Limpar conversa" }),
    );

    expect(
      await screen.findByText(/sobe quando a rede voltar/),
    ).toBeInTheDocument();
    expect(mocks.gravar).toHaveBeenCalledWith(
      "conversa-1",
      expect.objectContaining({ limpoAte: expect.any(String) }),
    );
  });
});
