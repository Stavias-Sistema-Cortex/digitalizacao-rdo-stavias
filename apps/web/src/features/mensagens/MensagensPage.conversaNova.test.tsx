// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "../../lib/api/apiError";
import type { ConversaLocalRecord } from "../../lib/db/db.types";

const mocks = vi.hoisted(() => ({
  listLocalConversations: vi.fn(),
  listLocalConversationPreviews: vi.fn(),
  listLocalMessages: vi.fn(),
  hasOnlineSession: vi.fn(),
  refreshConversationList: vi.fn(),
  refreshConversationHistory: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: PropsWithChildren) => <>{children}</>,
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  listObrasLocais: vi.fn().mockResolvedValue([]),
}));

vi.mock("../../lib/sync/syncEngine", () => ({ syncNow: vi.fn() }));

vi.mock("../../lib/sync/useSyncStatus", () => ({
  useSyncStatus: () => ({
    snapshot: { isOnline: true, lastSyncCompletedAt: null },
  }),
}));

vi.mock("../auth/authSession", () => ({
  getSession: () => null,
  hasOnlineSession: mocks.hasOnlineSession,
  isAlfa: () => false,
}));

vi.mock("./mensagensApi", () => ({
  downloadMessageAttachmentApi: vi.fn(),
  searchMessagesApi: vi.fn(),
  arquivarConversaApi: vi.fn(),
  limparConversaApi: vi.fn(),
}));

/*
 * A hidratação é dublada, mas o reconhecedor do 404 é o de verdade: é ele que
 * está em julgamento aqui.
 */
vi.mock("./mensagensHydration", async (importOriginal) => {
  const real = await importOriginal<typeof import("./mensagensHydration")>();
  return {
    conversaAindaNaoExisteNoServidor: real.conversaAindaNaoExisteNoServidor,
    refreshConversationHistory: mocks.refreshConversationHistory,
    refreshConversationList: mocks.refreshConversationList,
  };
});

vi.mock("./mensagensRepository", () => ({
  MESSAGES_CHANGED_EVENT: "cortex:test-messages-changed",
  localAttachmentBlob: vi.fn(),
  listLocalConversationPreviews: mocks.listLocalConversationPreviews,
  listLocalConversations: mocks.listLocalConversations,
  listLocalMessages: mocks.listLocalMessages,
  queueMessage: vi.fn(),
  retryMessage: vi.fn(),
  searchLocalMessages: vi.fn(),
  storeServerConversations: vi.fn(),
  storeServerMessages: vi.fn(),
  gravarPreferenciaDaConversa: vi.fn(),
  listarPreferenciasDeConversa: vi.fn(async () => new Map()),
}));

import { MensagensPage } from "./MensagensPage";

/** Conversa direta criada neste aparelho, ainda sem contrapartida no servidor. */
const conversaNova: ConversaLocalRecord = {
  id: "CONVERSA:nova",
  tipo: "DIRETA",
  titulo: null,
  obraId: null,
  equipeId: null,
  status: "ATIVA",
  participantes: [],
  criadaEm: "2026-08-14T12:00:00.000Z",
  atualizadaEm: "2026-08-14T12:00:00.000Z",
  versaoEntidade: null,
} as unknown as ConversaLocalRecord;

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/mensagens"]}>
      <MensagensPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  Object.defineProperty(navigator, "onLine", {
    configurable: true,
    value: true,
  });
  globalThis.ResizeObserver = class {
    observe() {}
    disconnect() {}
    unobserve() {}
  };
  vi.clearAllMocks();
  mocks.listLocalConversations.mockResolvedValue([conversaNova]);
  mocks.listLocalConversationPreviews.mockResolvedValue({});
  mocks.listLocalMessages.mockResolvedValue([]);
  mocks.hasOnlineSession.mockReturnValue(true);
  mocks.refreshConversationList.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
});

/**
 * Uma conversa criada no aparelho existe aqui antes de existir lá.
 *
 * <p>Ela aparece na lista, abre, aceita mensagem — e o servidor ainda não sabe
 * dela. Pedir o histórico nesse intervalo devolve 404, que é a resposta certa
 * para uma pergunta que ainda não faz sentido. Tratar isso como falha acendia
 * "Not Found" — em inglês, em vermelho — sobre uma tela em que tudo funcionava.
 */
describe("conversa que ainda não subiu", () => {
  it("não acusa erro quando o servidor não conhece o histórico", async () => {
    mocks.refreshConversationHistory.mockRejectedValue(
      new ApiError("Not Found", 404, null),
    );

    renderPage();

    await waitFor(() => {
      expect(mocks.refreshConversationHistory).toHaveBeenCalled();
    });

    expect(screen.queryByText("Not Found")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Fechar" }),
    ).not.toBeInTheDocument();
  });

  /*
   * O silêncio vale para o 404, e só para ele. Uma recusa de acesso ou uma
   * falha do servidor continuam sendo notícia — calar as duas trocaria uma
   * tarja incômoda por um aplicativo que esconde problema.
   */
  it("continua acusando o que não é ausência de histórico", async () => {
    mocks.refreshConversationHistory.mockRejectedValue(
      new ApiError("Acesso negado a esta conversa.", 403, null),
    );

    renderPage();

    expect(
      await screen.findByText("Acesso negado a esta conversa."),
    ).toBeInTheDocument();
  });

  /*
   * A tarja tem de apagar sozinha quando a tela volta a carregar. Sem isso o
   * aviso de uma tentativa antiga fica aceso sobre uma conversa que já abriu,
   * e a pessoa não tem como saber que ele já não descreve nada.
   */
  it("apaga a tarja ao abrir uma conversa que carrega", async () => {
    const user = userEvent.setup();
    mocks.listLocalConversations.mockResolvedValue([
      { ...conversaNova, tipo: "GRUPO", titulo: "Frente Norte" },
      {
        ...conversaNova,
        id: "CONVERSA:outra",
        tipo: "GRUPO",
        titulo: "Frente Sul",
      },
    ] as unknown as ConversaLocalRecord[]);
    mocks.refreshConversationHistory.mockRejectedValueOnce(
      new ApiError("Serviço indisponível.", 503, null),
    );

    renderPage();
    expect(
      await screen.findByText("Serviço indisponível."),
    ).toBeInTheDocument();

    mocks.refreshConversationHistory.mockResolvedValue(undefined);
    await user.click(screen.getByText("Frente Sul"));

    await waitFor(() => {
      expect(
        screen.queryByText("Serviço indisponível."),
      ).not.toBeInTheDocument();
    });
  });
});
