import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  clearSession: vi.fn(),
}));

vi.mock("../../features/auth/authSession", () => ({
  clearSession: mocks.clearSession,
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
    fetchMock.mockResolvedValue({ status: 200 } as Response);
    Object.assign(document, { cookie: "" });
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
