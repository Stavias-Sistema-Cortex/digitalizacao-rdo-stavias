// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router";
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
  resolveConversationId: vi.fn(),
  queueConversation: vi.fn(),
  queueMessage: vi.fn(),
  syncNow: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: PropsWithChildren) => <>{children}</>,
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  listObrasLocais: vi.fn().mockResolvedValue([]),
}));

vi.mock("../../lib/sync/syncEngine", () => ({ syncNow: mocks.syncNow }));

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
  MESSAGES_CHANGED_EVENT: "cortex-messages-changed",
  localAttachmentBlob: vi.fn(),
  listLocalConversationPreviews: mocks.listLocalConversationPreviews,
  listLocalConversations: mocks.listLocalConversations,
  listLocalMessages: mocks.listLocalMessages,
  queueConversation: mocks.queueConversation,
  queueMessage: mocks.queueMessage,
  resolveLocalConversationId: mocks.resolveConversationId,
  retryMessage: vi.fn(),
  searchLocalMessages: vi.fn(),
  storeServerConversations: vi.fn(),
  storeServerMessages: vi.fn(),
  gravarPreferenciaDaConversa: vi.fn(),
  confirmarPreferenciaDaConversaSincronizada: vi.fn(),
  listarPreferenciasPendentesDaConversa: vi.fn(async () => []),
  listarPreferenciasDeConversa: vi.fn(async () => new Map()),
}));

import { emitMessagesChanged } from "./mensagensEvents";
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

function LocationProbe() {
  return <output data-testid="location-search">{useLocation().search}</output>;
}

function renderPage(initialEntry = "/mensagens") {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <MensagensPage />
      <LocationProbe />
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
  mocks.refreshConversationHistory.mockResolvedValue(undefined);
  mocks.resolveConversationId.mockImplementation(async (id: string) => id);
  mocks.queueConversation.mockResolvedValue(conversaNova);
  mocks.syncNow.mockResolvedValue({ errors: 0, conflicts: 0 });
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
  it("abre a conversa recém-criada em vez de voltar para a lista", async () => {
    const user = userEvent.setup();
    let created = false;
    mocks.listLocalConversations.mockImplementation(async () =>
      created ? [conversaNova] : [],
    );
    mocks.queueConversation.mockImplementation(async () => {
      created = true;
      return conversaNova;
    });

    renderPage();

    await user.click(
      await screen.findByRole("button", { name: "Nova conversa" }),
    );
    await user.click(
      screen.getByRole("button", { name: "Criar conversa" }),
    );

    await waitFor(() => {
      expect(document.querySelector(".mensagens-workspace")).toHaveClass(
        "mensagens-workspace--thread",
      );
      expect(screen.getByTestId("location-search")).toHaveTextContent(
        `?conversa=${encodeURIComponent(conversaNova.id)}`,
      );
    });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

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

  it("continua na conversa canônica e leva URL e rascunho ao trocar o id", async () => {
    const user = userEvent.setup();
    const provisoria = {
      ...conversaNova,
      id: "CONVERSA:provisoria",
      participantes: [
        {
          colaboradorId: "COLABORADOR:abner",
          nome: "Abner",
          papel: "MEMBRO",
          status: "ATIVO",
          adicionadoEm: "2026-08-14T12:00:00.000Z",
        },
      ],
    } as unknown as ConversaLocalRecord;
    const canonica = {
      ...provisoria,
      id: "CONVERSA:canonica",
      versaoEntidade: 3,
    } as unknown as ConversaLocalRecord;
    const maisRecente = {
      ...conversaNova,
      id: "CONVERSA:mais-recente",
      tipo: "GRUPO",
      titulo: "Outra frente",
      atualizadaEm: "2026-08-19T15:00:00.000Z",
      versaoEntidade: 2,
    } as unknown as ConversaLocalRecord;
    mocks.listLocalConversations.mockResolvedValue([maisRecente, provisoria]);
    mocks.refreshConversationHistory.mockResolvedValue(undefined);

    renderPage("/mensagens?conversa=CONVERSA%3Aprovisoria");

    expect(
      await screen.findByRole("heading", { name: "Abner" }),
    ).toBeInTheDocument();
    const composer = screen.getByPlaceholderText("Mensagem");
    await user.type(composer, "Rascunho que não pode sumir");

    mocks.listLocalConversations.mockResolvedValue([maisRecente, canonica]);
    mocks.listLocalMessages.mockClear();
    await act(async () => {
      emitMessagesChanged({
        from: provisoria.id,
        to: canonica.id,
      });
    });

    await waitFor(() => {
      expect(screen.getByTestId("location-search")).toHaveTextContent(
        "?conversa=CONVERSA%3Acanonica",
      );
      expect(screen.getByPlaceholderText("Mensagem")).toHaveValue(
        "Rascunho que não pode sumir",
      );
      expect(mocks.listLocalMessages).toHaveBeenCalledWith(canonica.id);
    });
    expect(
      screen.getByRole("heading", { name: "Abner" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Outra frente" }),
    ).not.toBeInTheDocument();
  });

  it("não volta a ler o id provisório quando o envio termina depois do alias", async () => {
    const user = userEvent.setup();
    const provisoria = {
      ...conversaNova,
      id: "CONVERSA:provisoria-em-voo",
      participantes: [{
        colaboradorId: "COLABORADOR:abner",
        nome: "Abner",
        papel: "MEMBRO",
        status: "ATIVO",
        adicionadoEm: "2026-08-14T12:00:00.000Z",
      }],
    } as unknown as ConversaLocalRecord;
    const canonica = {
      ...provisoria,
      id: "CONVERSA:canonica-em-voo",
      versaoEntidade: 4,
    } as unknown as ConversaLocalRecord;
    let finishSync!: () => void;
    mocks.listLocalConversations.mockResolvedValue([provisoria]);
    mocks.queueMessage.mockResolvedValue({
      id: "MENSAGEM:local",
      conversaId: provisoria.id,
      anexos: [],
    });
    mocks.syncNow.mockImplementationOnce(() => new Promise((resolve) => {
      finishSync = () => resolve({ errors: 0, conflicts: 0 });
    }));
    mocks.resolveConversationId.mockResolvedValue(canonica.id);

    renderPage(`/mensagens?conversa=${encodeURIComponent(provisoria.id)}`);
    await screen.findByRole("heading", { name: "Abner" });
    await user.type(screen.getByPlaceholderText("Mensagem"), "Em campo");
    await user.click(screen.getByRole("button", { name: "Enviar mensagem" }));
    await waitFor(() => expect(mocks.syncNow).toHaveBeenCalledOnce());

    mocks.listLocalMessages.mockClear();
    mocks.listLocalConversations.mockResolvedValue([canonica]);
    await act(async () => {
      emitMessagesChanged({ from: provisoria.id, to: canonica.id });
      finishSync();
    });

    await waitFor(() => {
      expect(mocks.resolveConversationId).toHaveBeenCalledWith(provisoria.id);
      expect(mocks.listLocalMessages).toHaveBeenCalledWith(canonica.id);
    });
    expect(mocks.listLocalMessages).not.toHaveBeenCalledWith(provisoria.id);
  });
});
