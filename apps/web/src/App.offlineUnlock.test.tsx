// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

// Vitest hoists mock factories. Keeping the spy in a hoisted container makes
// the repository mock deterministic rather than depending on module order.
const { loadOfflineVaultMetadata } = vi.hoisted(() => ({
  loadOfflineVaultMetadata: vi.fn(),
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
  loadOfflineVaultMetadata,
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
  loadOfflineVaultMetadata.mockReset();
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
  it("offers the protected vault unlock route before PostgreSQL login when metadata exists offline", async () => {
    setNavigatorOnline(false);
    loadOfflineVaultMetadata.mockResolvedValue({
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
    });

    render(<App />);

    expect(await screen.findByRole("heading", {
      name: "Desbloquear dados deste dispositivo",
    })).toBeInTheDocument();
    expect(screen.queryByTestId("online-login")).not.toBeInTheDocument();
  });

  it("keeps a first-time offline device at the disabled online-login route", async () => {
    setNavigatorOnline(false);
    loadOfflineVaultMetadata.mockResolvedValue(null);

    render(<App />);

    expect(await screen.findByTestId("online-login")).toBeInTheDocument();
    expect(screen.queryByRole("heading", {
      name: "Desbloquear dados deste dispositivo",
    })).not.toBeInTheDocument();
  });
});
