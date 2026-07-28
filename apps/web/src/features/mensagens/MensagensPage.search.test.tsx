// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import {
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
  MESSAGES_CHANGED_EVENT: "cortex:test-messages-changed",
  localAttachmentBlob: vi.fn(),
  listLocalConversationPreviews: mocks.listLocalConversationPreviews,
  listLocalConversations: mocks.listLocalConversations,
  listLocalMessages: mocks.listLocalMessages,
  queueMessage: vi.fn(),
  retryMessage: vi.fn(),
  searchLocalMessages: mocks.searchLocalMessages,
  storeServerConversations: vi.fn(),
  storeServerMessages: vi.fn(),
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
  mocks.listLocalConversations.mockResolvedValue([]);
  mocks.listLocalConversationPreviews.mockResolvedValue({});
  mocks.listLocalMessages.mockResolvedValue([]);
  mocks.searchLocalMessages.mockResolvedValue([]);
  mocks.searchMessagesApi.mockResolvedValue([]);
  mocks.hasOnlineSession.mockReturnValue(true);
  mocks.refreshConversationList.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
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
    window.dispatchEvent(new Event("cortex:test-messages-changed"));

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

  it("searches after the conversation list changes from empty to populated", async () => {
    const user = userEvent.setup();
    mocks.hasOnlineSession.mockReturnValue(false);
    renderPage();

    await screen.findByText("Nenhuma conversa autorizada foi encontrada.");
    await user.type(screen.getByLabelText("Buscar no histórico"), "medição");

    mocks.listLocalConversations.mockResolvedValue([conversation]);
    window.dispatchEvent(new Event("cortex:test-messages-changed"));

    await screen.findByText("1 conversas autorizadas");
    await user.click(screen.getByRole("button", { name: "Buscar" }));

    await waitFor(() =>
      expect(mocks.searchLocalMessages).toHaveBeenCalledWith("medição"),
    );
  });
});
