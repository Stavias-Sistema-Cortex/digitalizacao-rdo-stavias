import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { despertarApi } from "./despertarApi";

describe("despertar a API antes do envio do CPF", () => {
  beforeEach(() => {
    vi.stubGlobal("navigator", { onLine: true });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("toca a prontidão sem credencial e sem cache", async () => {
    const fetchMock = vi.fn(async () => new Response("{}"));
    vi.stubGlobal("fetch", fetchMock);

    despertarApi();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/readiness");
    expect(init).toMatchObject({
      method: "GET",
      credentials: "omit",
      cache: "no-store",
    });
  });

  it("não empilha toques enquanto um já está em voo", async () => {
    let liberar: (() => void) | null = null;
    const fetchMock = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          liberar = () => resolve(new Response("{}"));
        }),
    );
    vi.stubGlobal("fetch", fetchMock);

    despertarApi();
    despertarApi();
    despertarApi();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    liberar!();
  });

  it("engole a falha: acordar o serviço não pode derrubar a tela", async () => {
    const fetchMock = vi.fn(async () => {
      throw new Error("rede indisponível");
    });
    vi.stubGlobal("fetch", fetchMock);

    expect(() => despertarApi()).not.toThrow();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  });

  it("não tenta nada com o dispositivo offline", () => {
    vi.stubGlobal("navigator", { onLine: false });
    const fetchMock = vi.fn(async () => new Response("{}"));
    vi.stubGlobal("fetch", fetchMock);

    despertarApi();

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
