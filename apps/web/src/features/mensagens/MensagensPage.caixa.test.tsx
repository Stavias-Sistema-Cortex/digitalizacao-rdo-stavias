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

  it("a gaveta lê as arquivadas sem gravar retrato no aparelho", async () => {
    abrir();

    fireEvent.click(await screen.findByRole("button", { name: "Arquivadas" }));

    await waitFor(() => expect(mocks.listar).toHaveBeenCalledWith(100, true));
    // Gravar esta leitura como autoritativa apagaria do aparelho tudo o que
    // não está arquivado — ou seja, a lista principal inteira.
    expect(mocks.storeServer).not.toHaveBeenCalled();
    expect(
      await screen.findByText("Você não arquivou nenhuma conversa."),
    ).toBeInTheDocument();
  });

  it("devolver à lista desarquiva pela porta pessoal", async () => {
    mocks.listar.mockResolvedValue([CONVERSA]);
    abrir();

    fireEvent.click(await screen.findByRole("button", { name: "Arquivadas" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "Devolver à lista" }),
    );

    await waitFor(() =>
      expect(mocks.arquivar).toHaveBeenCalledWith("conversa-1", false),
    );
  });
});
