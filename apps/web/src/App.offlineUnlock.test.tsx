// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

// Vitest hoists mock factories. Keeping the spy in a hoisted container makes
// the repository mock deterministic rather than depending on module order.
const mocks = vi.hoisted(() => ({
  hasCollaborativeOfflineGrantMetadata: vi.fn(),
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
  hasCollaborativeOfflineGrantMetadata:
    mocks.hasCollaborativeOfflineGrantMetadata,
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
    mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);

    render(<App />);

    expect(await screen.findByRole("textbox", { name: "CPF" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desbloquear com CPF" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Usar passkey" }))
      .not.toBeInTheDocument();
  });

  it("offers passkey unlock when only a PRF vault exists offline", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(false);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(passkeyVault);

    render(<App />);

    expect(await screen.findByRole("button", { name: "Usar passkey" }))
      .toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "CPF" }))
      .not.toBeInTheDocument();
  });

  it("offers CPF and passkey unlock together when both records exist", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(passkeyVault);

    render(<App />);

    expect(await screen.findByRole("textbox", { name: "CPF" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desbloquear com CPF" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Usar passkey" }))
      .toBeInTheDocument();
  });

  it("keeps a first-time offline device at the disabled online-login route", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(false);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);

    render(<App />);

    expect(await screen.findByTestId("online-login")).toBeInTheDocument();
    expect(screen.queryByRole("heading", {
      name: "Desbloquear dados deste dispositivo",
    })).not.toBeInTheDocument();
  });

  it("unlocks a matching CPF grant without exposing stored profile data", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);
    mocks.loadCollaborativeOfflineGrant.mockResolvedValue(cpfGrant);
    const user = userEvent.setup();

    render(<App />);

    await user.type(await screen.findByRole("textbox", { name: "CPF" }), "11144477735");
    await user.click(screen.getByRole("button", { name: "Desbloquear com CPF" }));

    await waitFor(() => {
      expect(mocks.loadCollaborativeOfflineGrant)
        .toHaveBeenCalledWith("11144477735");
      expect(mocks.unlockCollaborativeOfflineGrant)
        .toHaveBeenCalledWith("11144477735", cpfGrant);
      expect(mocks.initializeCortexDb).toHaveBeenCalled();
    });
    expect(screen.queryByText(cpfGrant.ownerId)).not.toBeInTheDocument();
  });

  it("rejects a mismatching CPF without attempting a grant unlock", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(true);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);
    mocks.loadCollaborativeOfflineGrant.mockResolvedValue(null);
    const user = userEvent.setup();

    render(<App />);

    await user.type(await screen.findByRole("textbox", { name: "CPF" }), "52998224725");
    await user.click(screen.getByRole("button", { name: "Desbloquear com CPF" }));

    // A recusa continua a mesma; o texto passou a dizer o que resolve, porque
    // quem lê isso está sem sinal e precisa saber que o login online que
    // habilita o offline acontece antes de sair da base.
    expect(await screen.findByRole("alert")).toHaveTextContent(
      /ainda não tem acesso offline neste aparelho/,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      /login com internet uma vez neste mesmo aparelho/,
    );
    expect(mocks.unlockCollaborativeOfflineGrant).not.toHaveBeenCalled();
    expect(mocks.initializeCortexDb).not.toHaveBeenCalled();
  });

  it("keeps a valid passkey fallback functional", async () => {
    setNavigatorOnline(false);
    mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(true);
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
  key: "a".repeat(64),
  versao: 1,
  cpfHash: "a".repeat(64),
  ownerId: "00000000-0000-4000-8000-000000000001",
  scopeFingerprint: "b".repeat(64),
  signedGrant: {
    keyId: "offline-test-v1",
    payload: "payload",
    signature: "signature",
    publicKeySpki: "public-key",
  },
  serverKeyFingerprint: "c".repeat(43),
  atualizadoEm: "2026-07-26T00:00:00Z",
};
