// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ConversaLocalRecord } from "../../lib/db/db.types";

const mocks = vi.hoisted(() => ({
  listLocalConversations: vi.fn(),
  listLocalConversationPreviews: vi.fn(),
  listLocalMessages: vi.fn(),
  searchLocalMessages: vi.fn(),
  searchMessagesApi: vi.fn(),
  hasOnlineSession: vi.fn(),
  refreshConversationList: vi.fn(),
  reserveMessagingRequestOrdinal: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: PropsWithChildren) => <>{children}</>,
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  listObrasLocais: vi.fn().mockResolvedValue([]),
}));

vi.mock("../../lib/sync/syncEngine", () => ({
  syncNow: vi.fn(),
}));

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
  searchMessagesApi: mocks.searchMessagesApi,
}));

vi.mock("./mensagensHydration", () => ({
  refreshConversationHistory: vi.fn(),
  refreshConversationList: mocks.refreshConversationList,
}));

vi.mock("./mensagensRepository", () => ({
  MESSAGES_CHANGED_EVENT: "cortex-messages-changed",
  localAttachmentBlob: vi.fn(),
  listLocalConversationPreviews: mocks.listLocalConversationPreviews,
  listLocalConversations: mocks.listLocalConversations,
  listLocalMessages: mocks.listLocalMessages,
  queueMessage: vi.fn(),
  reserveMessagingRequestOrdinal: mocks.reserveMessagingRequestOrdinal,
  retryMessage: vi.fn(),
  searchLocalMessages: mocks.searchLocalMessages,
  storeServerConversations: vi.fn(),
  storeServerMessages: vi.fn(),
  gravarPreferenciaDaConversa: vi.fn(),
  resolveLocalConversationId: vi.fn(async (id: string) => id),
  listarPreferenciasDeConversa: vi.fn(async () => new Map()),
}));

import { MensagensPage } from "./MensagensPage";

const conversation: ConversaLocalRecord = {
  id: "CONVERSA:1",
  tipo: "GRUPO",
  titulo: "Obra Centro",
  obraId: "OBRA:1",
  equipeId: null,
  status: "ATIVA",
  participantes: [],
  criadaEm: "2026-07-28T12:00:00.000Z",
  atualizadaEm: "2026-07-28T12:00:00.000Z",
  versaoEntidade: 1,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/mensagens"]}>
      <MensagensPage />
    </MemoryRouter>,
  );
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

class FakeBroadcastChannel {
  static instances = new Set<FakeBroadcastChannel>();

  readonly name: string;
  readonly listeners = new Set<(event: MessageEvent<unknown>) => void>();
  closed = false;

  constructor(name: string) {
    this.name = name;
    FakeBroadcastChannel.instances.add(this);
  }

  addEventListener(
    type: string,
    listener: (event: MessageEvent<unknown>) => void,
  ) {
    if (type === "message") this.listeners.add(listener);
  }

  removeEventListener(
    type: string,
    listener: (event: MessageEvent<unknown>) => void,
  ) {
    if (type === "message") this.listeners.delete(listener);
  }

  postMessage(data: unknown) {
    for (const channel of FakeBroadcastChannel.instances) {
      if (channel !== this && channel.name === this.name && !channel.closed) {
        for (const listener of channel.listeners) {
          listener({ data } as MessageEvent<unknown>);
        }
      }
    }
  }

  close() {
    this.closed = true;
    FakeBroadcastChannel.instances.delete(this);
  }
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
  FakeBroadcastChannel.instances.clear();
  mocks.listLocalConversations.mockResolvedValue([]);
  mocks.listLocalConversationPreviews.mockResolvedValue({});
  mocks.listLocalMessages.mockResolvedValue([]);
  mocks.searchLocalMessages.mockResolvedValue([]);
  mocks.searchMessagesApi.mockResolvedValue([]);
  mocks.hasOnlineSession.mockReturnValue(true);
  mocks.refreshConversationList.mockResolvedValue(undefined);
  mocks.reserveMessagingRequestOrdinal.mockResolvedValue(1);
});

afterEach(() => {
  cleanup();
  for (const channel of [...FakeBroadcastChannel.instances]) channel.close();
  vi.unstubAllGlobals();
});

describe("MensagensPage search", () => {
  it("shows the empty-conversation search state without querying messages", async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText("Nenhuma conversa autorizada foi encontrada.");
    await user.type(screen.getByLabelText("Buscar no histórico"), "medição");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    expect(
      await screen.findByText("Crie uma conversa primeiro."),
    ).toBeInTheDocument();
    expect(mocks.searchLocalMessages).not.toHaveBeenCalled();
    expect(mocks.searchMessagesApi).not.toHaveBeenCalled();
    expect(mocks.refreshConversationList).toHaveBeenCalledOnce();
  });

  it("does not submit a search while conversations are loading", () => {
    mocks.listLocalConversations.mockReturnValue(new Promise(() => {}));
    renderPage();

    const searchInput = screen.getByLabelText("Buscar no histórico");
    expect(searchInput).toBeDisabled();

    fireEvent.submit(screen.getByRole("search"));

    expect(mocks.searchLocalMessages).not.toHaveBeenCalled();
    expect(mocks.searchMessagesApi).not.toHaveBeenCalled();
    expect(
      screen.queryByText("Crie uma conversa primeiro."),
    ).not.toBeInTheDocument();
  });

  it("preserves a local-list failure without rendering an empty state", async () => {
    mocks.listLocalConversations.mockRejectedValue(
      new Error("Falha ao ler conversas locais."),
    );
    renderPage();

    expect(
      await screen.findByRole("alert"),
    ).toHaveTextContent("Falha ao ler conversas locais.");
    expect(screen.getByLabelText("Buscar no histórico")).toBeDisabled();

    fireEvent.submit(screen.getByRole("search"));

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Falha ao ler conversas locais.",
    );
    expect(
      screen.queryByText("Nenhuma conversa autorizada foi encontrada."),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("Crie uma conversa primeiro."),
    ).not.toBeInTheDocument();
    expect(mocks.searchLocalMessages).not.toHaveBeenCalled();
    expect(mocks.searchMessagesApi).not.toHaveBeenCalled();
  });

  it("preserves a remote refresh failure after a search submission", async () => {
    mocks.refreshConversationList.mockRejectedValue(
      new Error("Falha ao atualizar conversas remotas."),
    );
    renderPage();

    expect(
      await screen.findByRole("alert"),
    ).toHaveTextContent("Falha ao atualizar conversas remotas.");
    expect(screen.getByLabelText("Buscar no histórico")).toBeDisabled();

    fireEvent.submit(screen.getByRole("search"));

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Falha ao atualizar conversas remotas.",
    );
    expect(
      screen.queryByText("Nenhuma conversa autorizada foi encontrada."),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("Crie uma conversa primeiro."),
    ).not.toBeInTheDocument();
    expect(mocks.searchLocalMessages).not.toHaveBeenCalled();
    expect(mocks.searchMessagesApi).not.toHaveBeenCalled();
  });

  it("hides an existing empty-search state when a later reload fails", async () => {
    const user = userEvent.setup();
    mocks.hasOnlineSession.mockReturnValue(false);
    renderPage();

    await screen.findByText("Nenhuma conversa autorizada foi encontrada.");
    await user.type(screen.getByLabelText("Buscar no histórico"), "medição");
    await user.click(screen.getByRole("button", { name: "Buscar" }));
    await screen.findByText("Crie uma conversa primeiro.");

    mocks.listLocalConversations.mockRejectedValue(
      new Error("Falha ao recarregar conversas."),
    );
    window.dispatchEvent(new Event("cortex-messages-changed"));

    expect(
      await screen.findByRole("alert"),
    ).toHaveTextContent("Falha ao recarregar conversas.");
    expect(
      screen.queryByText("Crie uma conversa primeiro."),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("0 resultado(s)")).not.toBeInTheDocument();
  });

  it("searches cached conversations while offline", async () => {
    const user = userEvent.setup();
    Object.defineProperty(navigator, "onLine", {
      configurable: true,
      value: false,
    });
    mocks.listLocalConversations.mockResolvedValue([conversation]);
    renderPage();

    await screen.findByText("1 conversas autorizadas");
    await user.type(screen.getByLabelText("Buscar no histórico"), "medição");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() =>
      expect(mocks.searchLocalMessages).toHaveBeenCalledWith("medição"),
    );
    expect(mocks.searchMessagesApi).not.toHaveBeenCalled();
  });

  it("recalculates visible search results when the local message snapshot changes", async () => {
    const user = userEvent.setup();
    mocks.hasOnlineSession.mockReturnValue(false);
    mocks.listLocalConversations.mockResolvedValue([conversation]);
    mocks.searchLocalMessages
      .mockResolvedValueOnce([{
        id: "MENSAGEM:antiga",
        conversaId: conversation.id,
        autorId: "COLABORADOR:1",
        autorNome: "Operador",
        corpo: "Medição que acabou de ser limpa",
        status: "ATIVA",
        clientMutationId: "MUTACAO:1",
        criadaNoClienteEm: "2026-08-19T12:00:00.000Z",
        criadaEm: "2026-08-19T12:00:00.000Z",
        editadaEm: null,
        deletadaEm: null,
        versaoEntidade: 1,
        syncStatus: "SINCRONIZADO",
        ultimoErro: null,
        updatedAt: "2026-08-19T12:00:00.000Z",
        anexos: [],
      }])
      .mockResolvedValueOnce([]);
    renderPage();

    await screen.findByText("1 conversas autorizadas");
    await user.type(screen.getByLabelText("Buscar no histórico"), "medição");
    await user.click(screen.getByRole("button", { name: "Buscar" }));
    expect(
      await screen.findByText("Medição que acabou de ser limpa"),
    ).toBeVisible();

    window.dispatchEvent(new Event("cortex-messages-changed"));

    await waitFor(() =>
      expect(mocks.searchLocalMessages).toHaveBeenCalledTimes(2),
    );
    expect(
      screen.queryByText("Medição que acabou de ser limpa"),
    ).not.toBeInTheDocument();
    expect(screen.getByText("0 resultado(s)")).toBeVisible();
  });

  it("does not let an older message read overwrite a newer revoked snapshot", async () => {
    mocks.hasOnlineSession.mockReturnValue(false);
    mocks.listLocalConversations.mockResolvedValue([conversation]);
    let finishOldRead!: (
      messages: Awaited<ReturnType<typeof mocks.listLocalMessages>>,
    ) => void;
    const oldRead = new Promise<
      Awaited<ReturnType<typeof mocks.listLocalMessages>>
    >((resolve) => {
      finishOldRead = resolve;
    });
    mocks.listLocalMessages
      .mockReturnValueOnce(oldRead)
      .mockResolvedValueOnce([]);
    renderPage();

    await waitFor(() =>
      expect(mocks.listLocalMessages).toHaveBeenCalledTimes(1),
    );
    window.dispatchEvent(new Event("cortex-messages-changed"));
    await waitFor(() =>
      expect(mocks.listLocalMessages).toHaveBeenCalledTimes(2),
    );

    await act(async () => {
      finishOldRead([{
        id: "MENSAGEM:revogada",
        conversaId: conversation.id,
        autorId: "COLABORADOR:1",
        autorNome: "Operador",
        corpo: "Corpo da leitura anterior à revogação",
        status: "ATIVA",
        clientMutationId: "MUTACAO:revogada",
        criadaNoClienteEm: "2026-08-19T12:00:00.000Z",
        criadaEm: "2026-08-19T12:00:00.000Z",
        editadaEm: null,
        deletadaEm: null,
        versaoEntidade: 1,
        syncStatus: "SINCRONIZADO",
        ultimoErro: null,
        updatedAt: "2026-08-19T12:00:00.000Z",
        anexos: [],
      }]);
      await Promise.resolve();
    });

    expect(
      screen.queryByText("Corpo da leitura anterior à revogação"),
    ).not.toBeInTheDocument();
  });

  it("does not let an older overview read restore a revoked conversation or preview", async () => {
    mocks.hasOnlineSession.mockReturnValue(false);
    const oldConversations = deferred<ConversaLocalRecord[]>();
    const oldPreviews = deferred<Record<string, {
      messageId: string;
      text: string;
      authorId: string;
      authorName: string;
      at: string;
      syncStatus: "SINCRONIZADO";
    }>>();
    mocks.listLocalConversations
      .mockReturnValueOnce(oldConversations.promise)
      .mockResolvedValueOnce([]);
    mocks.listLocalConversationPreviews
      .mockReturnValueOnce(oldPreviews.promise)
      .mockResolvedValueOnce({});
    renderPage();

    await waitFor(() => {
      expect(mocks.listLocalConversations).toHaveBeenCalledTimes(1);
      expect(mocks.listLocalConversationPreviews).toHaveBeenCalledTimes(1);
    });
    window.dispatchEvent(new Event("cortex-messages-changed"));
    await screen.findByText("Nenhuma conversa autorizada foi encontrada.");

    await act(async () => {
      oldConversations.resolve([conversation]);
      oldPreviews.resolve({
        [conversation.id]: {
          messageId: "MENSAGEM:preview-revogado",
          text: "Preview anterior à revogação",
          authorId: "COLABORADOR:1",
          authorName: "Operador",
          at: "2026-08-19T12:00:00.000Z",
          syncStatus: "SINCRONIZADO",
        },
      });
      await Promise.resolve();
    });

    expect(screen.queryAllByText("Obra Centro")).toHaveLength(0);
    expect(
      screen.queryByText("Preview anterior à revogação"),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText("Nenhuma conversa autorizada foi encontrada."),
    ).toBeVisible();
  });

  it("invalidates this document when another tab publishes a revocation", async () => {
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
    mocks.hasOnlineSession.mockReturnValue(false);
    mocks.listLocalConversations.mockResolvedValue([conversation]);
    mocks.listLocalConversationPreviews.mockResolvedValue({
      [conversation.id]: {
        messageId: "MENSAGEM:preview",
        text: "Preview remoto",
        authorId: "COLABORADOR:1",
        authorName: "Operador",
        at: "2026-08-19T12:00:00.000Z",
        syncStatus: "SINCRONIZADO",
      },
    });
    const rendered = renderPage();
    expect((await screen.findAllByText("Obra Centro")).length).toBeGreaterThan(0);
    await waitFor(() =>
      expect(FakeBroadcastChannel.instances.size).toBe(1),
    );

    mocks.listLocalConversations.mockResolvedValue([]);
    mocks.listLocalConversationPreviews.mockResolvedValue({});
    const tabA = new FakeBroadcastChannel("cortex-messages-changed-v1");
    act(() => {
      tabA.postMessage({
        type: "cortex-messages-changed",
        remap: null,
      });
    });

    expect(
      await screen.findByText("Nenhuma conversa autorizada foi encontrada."),
    ).toBeVisible();
    expect(screen.queryAllByText("Obra Centro")).toHaveLength(0);
    const tabB = [...FakeBroadcastChannel.instances].find(
      (channel) => channel !== tabA,
    );
    expect(tabB).toBeDefined();

    rendered.unmount();
    expect(tabB?.closed).toBe(true);
    tabA.close();
  });

  it("searches after the conversation list changes from empty to populated", async () => {
    const user = userEvent.setup();
    mocks.hasOnlineSession.mockReturnValue(false);
    renderPage();

    await screen.findByText("Nenhuma conversa autorizada foi encontrada.");
    await user.type(screen.getByLabelText("Buscar no histórico"), "medição");

    mocks.listLocalConversations.mockResolvedValue([conversation]);
    window.dispatchEvent(new Event("cortex-messages-changed"));

    await screen.findByText("1 conversas autorizadas");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() =>
      expect(mocks.searchLocalMessages).toHaveBeenCalledWith("medição"),
    );
  });
});
