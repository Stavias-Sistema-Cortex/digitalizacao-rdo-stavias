// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

// Vitest hoists mock factories. Keeping the spy in a hoisted container makes
// the repository mock deterministic rather than depending on module order.
const mocks = vi.hoisted(() => ({
  hasCollaborativePasswordVaultMetadata: vi.fn(),
  initializeCortexDb: vi.fn(),
  loadCollaborativeOfflineGrant: vi.fn(),
  loadOfflineVaultMetadata: vi.fn(),
  unlockCollaborativeOfflineGrant: vi.fn(),
  unlockOfflineVault: vi.fn(),
}));

const originalNavigatorOnlineDescriptor = Object.getOwnPropertyDescriptor(
  navigator,
  "onLine",
);

function setNavigatorOnline(value: boolean) {
  Object.defineProperty(navigator, "onLine", {
    configurable: true,
    value,
  });
}

vi.mock("./features/auth/authSession", () => ({
  AUTH_SESSION_CHANGED_EVENT: "cortex-auth-session-changed",
  getSession: () => null,
  isAlfa: () => false,
}));

vi.mock("./features/auth/offlineVaultRepository", () => ({
  hasCollaborativePasswordVaultMetadata:
    mocks.hasCollaborativePasswordVaultMetadata,
  loadOfflineVaultMetadata: mocks.loadOfflineVaultMetadata,
}));

vi.mock("./features/auth/collaborativeOfflineGrant", () => ({
  loadCollaborativeOfflineGrant: mocks.loadCollaborativeOfflineGrant,
  unlockCollaborativeOfflineGrant: mocks.unlockCollaborativeOfflineGrant,
}));

vi.mock("./features/auth/offlineVault", () => ({
  unlockOfflineVault: mocks.unlockOfflineVault,
}));

vi.mock("./lib/db/cortexDb", () => ({
  initializeCortexDb: mocks.initializeCortexDb,
}));

vi.mock("./appAutomaticSync", () => ({
  useAppAutomaticSync: () => undefined,
}));

vi.mock("./features/auth/LoginPage", () => ({
  LoginPage: () => <main data-testid="online-login">Login online</main>,
}));

import App from "./App";

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  if (originalNavigatorOnlineDescriptor) {
    Object.defineProperty(
      navigator,
      "onLine",
      originalNavigatorOnlineDescriptor,
    );
  } else {
    Reflect.deleteProperty(navigator, "onLine");
  }
});

describe("App offline authentication entry", () => {
  it("offers direct CPF unlock when only a collaborative grant exists offline", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativePasswordVaultMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);

    render(<App />);

    expect(await screen.findByRole("textbox", { name: "CPF" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Senha")).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("Senha"))
      .toHaveAttribute("autocomplete", "current-password");
    expect(screen.getByRole("button", { name: "Desbloquear acesso offline" }))
      .toBeInTheDocument();
    expect(screen.getByText(
      "Este aparelho precisa de um primeiro login com conexão antes de funcionar offline.",
    )).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Usar passkey" }))
      .not.toBeInTheDocument();
  });

  it("offers passkey unlock when only a PRF vault exists offline", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativePasswordVaultMetadata.mockResolvedValue(false);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(passkeyVault);

    render(<App />);

    expect(await screen.findByRole("button", { name: "Usar passkey" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "CPF" }))
      .not.toBeInTheDocument();
  });

  it("offers CPF and passkey unlock together when both records exist", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativePasswordVaultMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(passkeyVault);

    render(<App />);

    expect(await screen.findByRole("textbox", { name: "CPF" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desbloquear acesso offline" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Usar passkey" }))
      .toBeInTheDocument();
  });

  it("keeps a first-time offline device at the disabled online-login route", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativePasswordVaultMetadata.mockResolvedValue(false);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);

    render(<App />);

    expect(await screen.findByTestId("online-login")).toBeInTheDocument();
    expect(screen.queryByRole("heading", {
      name: "Desbloquear dados deste dispositivo",
    })).not.toBeInTheDocument();
  });

  it("unlocks a matching CPF grant without exposing stored profile data", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativePasswordVaultMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);
    mocks.loadCollaborativeOfflineGrant.mockResolvedValue(cpfGrant);
    const user = userEvent.setup();

    render(<App />);

    await user.type(await screen.findByRole("textbox", { name: "CPF" }), "11144477735");
    await user.type(screen.getByLabelText("Senha"), "Senha individual forte 123!");
    await user.click(screen.getByRole("button", { name: "Desbloquear acesso offline" }));

    await waitFor(() => {
      expect(mocks.loadCollaborativeOfflineGrant)
        .toHaveBeenCalledWith("11144477735");
      expect(mocks.unlockCollaborativeOfflineGrant)
        .toHaveBeenCalledWith(
          "11144477735",
          "Senha individual forte 123!",
          cpfGrant,
        );
      expect(mocks.initializeCortexDb).toHaveBeenCalled();
    });
    expect(screen.queryByText(cpfGrant.ciphertext)).not.toBeInTheDocument();
  });

  it("rejects a mismatching CPF without attempting a grant unlock", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativePasswordVaultMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);
    mocks.loadCollaborativeOfflineGrant.mockResolvedValue(null);
    const user = userEvent.setup();

    render(<App />);

    await user.type(await screen.findByRole("textbox", { name: "CPF" }), "52998224725");
    await user.type(screen.getByLabelText("Senha"), "Senha individual forte 123!");
    await user.click(screen.getByRole("button", { name: "Desbloquear acesso offline" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não foi possível liberar o acesso offline neste aparelho.",
    );
    expect(mocks.unlockCollaborativeOfflineGrant).not.toHaveBeenCalled();
    expect(mocks.initializeCortexDb).not.toHaveBeenCalled();
  });

  it.each([
    ["wrong password", new Error("senha")],
    ["tampered vault", new Error("ciphertext")],
    ["expired grant", new Error("expirou")],
  ])("collapses %s to the same offline unlock alert", async (_label, cause) => {
    setNavigatorOnline(false);
    mocks.hasCollaborativePasswordVaultMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);
    mocks.loadCollaborativeOfflineGrant.mockResolvedValue(cpfGrant);
    mocks.unlockCollaborativeOfflineGrant.mockRejectedValue(cause);
    const user = userEvent.setup();

    render(<App />);

    await user.type(
      await screen.findByRole("textbox", { name: "CPF" }),
      "11144477735",
    );
    await user.type(
      screen.getByLabelText("Senha"),
      "Senha individual forte 123!",
    );
    await user.click(screen.getByRole("button", {
      name: "Desbloquear acesso offline",
    }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não foi possível liberar o acesso offline neste aparelho.",
    );
    expect(mocks.initializeCortexDb).not.toHaveBeenCalled();
  });

  it("keeps a valid passkey fallback functional", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativePasswordVaultMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(passkeyVault);
    mocks.unlockOfflineVault.mockResolvedValue("UNLOCKED");
    const user = userEvent.setup();

    render(<App />);

    await user.click(await screen.findByRole("button", { name: "Usar passkey" }));

    await waitFor(() => {
      expect(mocks.unlockOfflineVault).toHaveBeenCalledWith(passkeyVault);
      expect(mocks.initializeCortexDb).toHaveBeenCalled();
    });
  });
});

const passkeyVault = {
      key: "vault-1",
      versao: 1,
      ownerId: "11111111-1111-4111-8111-111111111111",
      scopeFingerprint: "scope-1",
      credentialId: "credential-1",
      rpId: "localhost",
      prfSalt: "salt-1",
      iv: "iv-1",
      ciphertext: "ciphertext-1",
      serverKeyFingerprint: "server-key-1",
      atualizadoEm: "2026-07-26T00:00:00Z",
};

const cpfGrant = {
  key: "20000000-0000-4000-8000-000000000001",
  versao: 3,
  cpfSalt: "s".repeat(22),
  cpfVerifier: "v".repeat(43),
  passwordSalt: "p".repeat(22),
  kdf: "PBKDF2-SHA256",
  kdfIterations: 600_000,
  iv: "i".repeat(16),
  ciphertext: "ciphertext",
  serverKeyFingerprint: "c".repeat(43),
  atualizadoEm: "2026-07-26T00:00:00Z",
  failedAttemptState: {
    windowStartedAt: null,
    failures: 0,
    blockedUntil: null,
  },
};
