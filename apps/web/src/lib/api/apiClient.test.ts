import { readFileSync } from "node:fs";

import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  clearSession: vi.fn(),
  hasOfflineSession: vi.fn(),
}));

vi.mock("../../features/auth/authSession", () => ({
  clearSession: mocks.clearSession,
  hasOfflineSession: mocks.hasOfflineSession,
}));

import {
  ApiError,
  apiError,
  apiFetch,
  apiUrl,
  responseErrorMessage,
} from "./apiClient";

const fetchMock = vi.fn();
const csrfToken = "c".repeat(43);
const localValues = new Map<string, string>();
let rejectIsolationMarkerWrite = false;

vi.stubGlobal("fetch", fetchMock);
vi.stubGlobal("window", {
  setTimeout,
  clearTimeout,
  location: {
    hostname: "cortex.example.invalid",
    origin: "https://cortex.example.invalid",
    protocol: "https:",
  },
});
vi.stubGlobal("document", { cookie: "" });
vi.stubGlobal("localStorage", {
  getItem: (key: string) => localValues.get(key) ?? null,
  removeItem: (key: string) => localValues.delete(key),
  setItem: (key: string, value: string) => {
    if (
      key === "cortex.auth.remote-session-isolation" &&
      rejectIsolationMarkerWrite
    ) {
      throw new Error("storage unavailable");
    }
    localValues.set(key, value);
  },
});

describe("responseErrorMessage", () => {
  it("traduz bloqueio de CORS para uma mensagem operacional", () => {
    expect(responseErrorMessage("Invalid CORS request", 403)).toContain(
      "A API recusou a origem desta tela",
    );

    expect(
      responseErrorMessage({ message: "Invalid CORS request" }, 403),
    ).toContain("CORS");
  });

  it("preserva somente códigos de máquina delimitados no ApiError", () => {
    const activation = apiError(
      {
        code: "CORTEX_ACTIVATION_ONLY",
        message: "Ativação inicial do Córtex em andamento.",
        internal: { database: "não pode atravessar" },
      },
      503,
    );
    expect(activation).toBeInstanceOf(ApiError);
    expect(activation).toMatchObject({
      status: 503,
      code: "CORTEX_ACTIVATION_ONLY",
    });
    expect(activation.message).toBe(
      "Ativação inicial do Córtex em andamento.",
    );
    expect(Object.keys(activation)).not.toContain("body");
    expect(apiError({ code: "raw@email.example" }, 503).code).toBeNull();
    expect(
      apiError(
        {
          message: "Senha, CPF e detalhes do banco não devem atravessar.",
          detail: "stack trace",
        },
        500,
      ).message,
    ).toBe("Não foi possível concluir a solicitação ao Córtex.");
  });
});

describe("apiFetch cookie session", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.unstubAllEnvs();
    localValues.clear();
    rejectIsolationMarkerWrite = false;
    mocks.hasOfflineSession.mockReturnValue(false);
    fetchMock.mockResolvedValue({ status: 200 } as Response);
    Object.assign(document, { cookie: "" });
  });

  it("does not reach fetch for operational or session probes while remote isolation is persisted", async () => {
    localStorage.setItem("cortex.auth.remote-session-isolation", "1");

    await expect(apiFetch("/obras")).rejects.toMatchObject({
      kind: "REMOTE_SESSION_ISOLATED",
    });
    await expect(apiFetch("/auth/session")).rejects.toMatchObject({
      kind: "REMOTE_SESSION_ISOLATED",
    });

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("does not reach fetch while an in-memory offline session is active", async () => {
    mocks.hasOfflineSession.mockReturnValue(true);

    await expect(apiFetch("/obras")).rejects.toMatchObject({
      kind: "REMOTE_SESSION_ISOLATED",
    });

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("keeps this live tab gated when the durable isolation marker write fails", async () => {
    const isolation = await import("../../features/auth/remoteSessionIsolation");
    rejectIsolationMarkerWrite = true;

    expect(() => isolation.markRemoteSessionIsolation()).toThrow(
      "isolar a sessão remota",
    );
    await expect(apiFetch("/obras")).rejects.toMatchObject({
      kind: "REMOTE_SESSION_ISOLATED",
    });
    expect(fetchMock).not.toHaveBeenCalled();

    rejectIsolationMarkerWrite = false;
    isolation.clearRemoteSessionIsolation();
  });

  it("permits cookie revocation only through its named exact POST path", async () => {
    localStorage.setItem("cortex.auth.remote-session-isolation", "1");

    await expect(
      apiFetch("/auth/logout", { method: "POST" }),
    ).rejects.toMatchObject({ kind: "REMOTE_SESSION_ISOLATED" });
    expect(fetchMock).not.toHaveBeenCalled();

    const client = await import("./apiClient") as typeof import("./apiClient") & {
      revokeRemoteSessionCookie?: () => Promise<Response>;
    };
    expect(client.revokeRemoteSessionCookie).toBeTypeOf("function");

    await client.revokeRemoteSessionCookie!();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toContain("/auth/logout");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "POST" });
  });

  it("permits the isolated CPF grant follow-up only through its named helper", async () => {
    localStorage.setItem("cortex.auth.remote-session-isolation", "1");
    const client = await import("./apiClient") as typeof import("./apiClient") & {
      fetchFreshCpfOfflineGrant?: () => Promise<Response>;
    };
    expect(client.fetchFreshCpfOfflineGrant).toBeTypeOf("function");

    await client.fetchFreshCpfOfflineGrant!();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toContain("/auth/offline-grant");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "POST" });
  });

  it("permits only the exact fresh-authentication POST allowlist", async () => {
    const client = await import("./apiClient") as typeof import("./apiClient") & {
      freshAuthenticationFetch?: (
        path: string,
        options: RequestInit,
      ) => Promise<Response>;
    };
    expect(client.freshAuthenticationFetch).toBeTypeOf("function");

    await expect(
      client.freshAuthenticationFetch!("/obras", { method: "POST" }),
    ).rejects.toThrow("autenticação nova inválida");
    await expect(
      client.freshAuthenticationFetch!("/auth/login", { method: "GET" }),
    ).rejects.toThrow("autenticação nova inválida");

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejeita API em outro hostname porque o CSRF é host-only", () => {
    vi.stubEnv(
      "VITE_CORTEX_API_BASE_URL",
      "https://api.example.invalid/api",
    );

    expect(() => apiUrl("/obras")).toThrow(
      "mesmo hostname",
    );
  });

  it("permite porta diferente no mesmo hostname para desenvolvimento", () => {
    vi.stubEnv(
      "VITE_CORTEX_API_BASE_URL",
      "https://cortex.example.invalid:8443/api",
    );

    expect(apiUrl("/obras")).toBe(
      "https://cortex.example.invalid:8443/api/obras",
    );
  });

  it("normaliza base raiz relativa e rejeita valor relativo ambíguo", () => {
    vi.stubEnv("VITE_CORTEX_API_BASE_URL", "/gateway/api/");
    expect(apiUrl("/obras")).toBe("/gateway/api/obras");

    vi.stubEnv("VITE_CORTEX_API_BASE_URL", "api");
    expect(() => apiUrl("/obras")).toThrow(
      "absoluta http(s) ou iniciar com /",
    );
  });

  it("rejeita protocolo diferente, protocolo não HTTP e componentes inseguros", () => {
    for (const invalidBaseUrl of [
      "http://cortex.example.invalid/api",
      "ftp://cortex.example.invalid/api",
      "https://user:password@cortex.example.invalid/api",
      "https://cortex.example.invalid/api?tenant=1",
      "https://cortex.example.invalid/api#fragment",
    ]) {
      vi.stubEnv(
        "VITE_CORTEX_API_BASE_URL",
        invalidBaseUrl,
      );
      expect(() => apiUrl("/obras")).toThrow();
    }
  });

  it("sempre inclui credenciais e nunca produz Authorization ou Bearer", async () => {
    await apiFetch("/obras", {
      credentials: "omit",
      headers: {
        Authorization: "Bearer legado",
        "X-Request-Id": "sintético",
      },
    });

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(options.headers);
    expect(options.credentials).toBe("include");
    expect(headers.get("Authorization")).toBeNull();
    expect(JSON.stringify(options)).not.toContain("Bearer");
    expect(headers.get("X-Request-Id")).toBe("sintético");
  });

  it("usa o token CSRF do cookie em mutações e não aceita sobrescrita", async () => {
    Object.assign(document, {
      cookie: `tema=claro; cortex_csrf=${csrfToken}`,
    });

    await apiFetch("/obras", {
      method: "PATCH",
      headers: {
        "X-CSRF-Token": "token_forjado_abcdefghijklmnopqrstuvwxyz1",
      },
    });

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(options.headers);
    expect(headers.get("X-CSRF-Token")).toBe(
      csrfToken,
    );
  });

  it("tolera cookie ausente ou malformado sem lançar", async () => {
    Object.assign(document, { cookie: "cortex_csrf=%E0%A4%A" });

    await expect(
      apiFetch("/obras", { method: "DELETE" }),
    ).resolves.toMatchObject({ status: 200 });

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(new Headers(options.headers).get("X-CSRF-Token")).toBeNull();
  });

  it("limpa a memória após 401 protegido sem retry silencioso", async () => {
    fetchMock.mockResolvedValue({ status: 401 } as Response);

    await apiFetch("/obras");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(mocks.clearSession).toHaveBeenCalledTimes(1);
  });

  it("limpa 401 de registro de passkey, mas preserva rotas públicas exatas", async () => {
    fetchMock.mockResolvedValue({ status: 401 } as Response);

    await apiFetch("/auth/passkeys/registration/options", {
      method: "POST",
    });
    expect(mocks.clearSession).toHaveBeenCalledTimes(1);

    mocks.clearSession.mockClear();
    await apiFetch("/auth/login", {
      method: "POST",
    });
    expect(mocks.clearSession).not.toHaveBeenCalled();

    mocks.clearSession.mockClear();
    await apiFetch("/auth/passkeys/authentication/options", {
      method: "POST",
    });
    expect(mocks.clearSession).not.toHaveBeenCalled();
  });
});

describe("same-origin local runtime contract", () => {
  it("embeds only the root-relative API base in local and compose builds", () => {
    const packageJson = JSON.parse(
      readFileSync(
        new URL("../../../package.json", import.meta.url),
        "utf8",
      ),
    ) as { scripts: Record<string, string> };

    for (const script of ["build:local", "build:compose"]) {
      expect(packageJson.scripts[script]).toContain(
        "VITE_CORTEX_API_BASE_URL=/api",
      );
      expect(packageJson.scripts[script]).not.toMatch(
        /VITE_CORTEX_API_BASE_URL=https?:\/\//,
      );
      expect(packageJson.scripts[script]).not.toContain(
        "CORTEX_API_TARGET=",
      );
    }

    for (const script of [
      "dev:local",
      "dev:compose",
      "preview:local",
      "preview:compose",
    ]) {
      expect(packageJson.scripts[script]).toContain(
        "CORTEX_API_TARGET=http://127.0.0.1:",
      );
    }
  });

  it("derives loopback ports and the exact local web origin without normal OTP", () => {
    const compose = readFileSync(
      new URL("../../../../../compose.local.yml", import.meta.url),
      "utf8",
    );
    const runApi = readFileSync(
      new URL(
        "../../../../../scripts/dev/run-api.sh",
        import.meta.url,
      ),
      "utf8",
    );
    const localApplication = readFileSync(
      new URL(
        "../../../../api/src/main/resources/application-local.yml",
        import.meta.url,
      ),
      "utf8",
    );

    expect(compose).toContain(
      '"127.0.0.1:${CORTEX_API_PORT:-8081}:8080"',
    );
    expect(compose).toContain(
      '"127.0.0.1:${CORTEX_WEB_PORT:-5173}:8080"',
    );
    expect(compose).toContain(
      "CORTEX_CORS_ALLOWED_ORIGINS: http://localhost:${CORTEX_WEB_PORT:-5173}",
    );
    expect(compose).toContain(
      "CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS: http://localhost:${CORTEX_WEB_PORT:-5173}",
    );
    expect(compose).not.toContain("CORTEX_AUTH_OTP_HMAC_KEY_FILE");
    expect(compose).not.toContain("cortex_otp_hmac");

    expect(runApi).not.toContain(
      "cortex_require_secret_file CORTEX_AUTH_OTP_HMAC_KEY_FILE",
    );
    expect(runApi).toContain(
      'API_HEALTH_URL="http://127.0.0.1:${API_PORT}/api/health"',
    );

    expect(localApplication).toContain(
      "allowed-origins: ${CORTEX_CORS_ALLOWED_ORIGINS:http://localhost:${CORTEX_WEB_PORT:5173}}",
    );
    expect(localApplication).toContain(
      "allowed-origins: ${CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS:http://localhost:${CORTEX_WEB_PORT:5173}}",
    );
  });
});
