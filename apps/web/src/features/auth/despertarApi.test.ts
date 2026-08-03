import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { despertarApi } from "./despertarApi";

describe("despertar a API antes do envio do CPF", () => {
  beforeEach(() => {
    vi.stubGlobal("navigator", { onLine: true });
  });

  afterEach(async () => {
    // A insistência é assíncrona: sem drenar o que ficou pendente, o estado de
    // um teste vaza para o próximo e o guard de toque único mente.
    if (vi.isFakeTimers()) {
      await vi.runAllTimersAsync();
      vi.useRealTimers();
    }
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("aquece o caminho do login sem credencial e sem cache", async () => {
    const fetchMock = vi.fn(async () => new Response("{}"));
    vi.stubGlobal("fetch", fetchMock);

    despertarApi();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/wake");
    expect(init).toMatchObject({
      method: "GET",
      credentials: "omit",
      cache: "no-store",
    });
  });

  /**
   * O readiness também acorda o contêiner, mas cobra junto o round trip do
   * object storage e a evidência de release — trabalho que entrar no sistema
   * não usa. O toque tem que bater no caminho barato.
   */
  it("não paga o readiness para acordar o contêiner", async () => {
    const fetchMock = vi.fn(async () => new Response("{}"));
    vi.stubGlobal("fetch", fetchMock);

    despertarApi();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    expect(fetchMock.mock.calls[0][0]).not.toContain("readiness");
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
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  });

  /**
   * Enquanto o contêiner sobe, o roteador na frente dele responde na hora com
   * 502 — e o toque terminava aí, deixando a subida pela metade justamente
   * quando insistir era de graça.
   */
  it("insiste enquanto o roteador diz que o contêiner ainda sobe", async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn<() => Promise<Response>>()
      .mockResolvedValueOnce(new Response("", { status: 502 }))
      .mockResolvedValueOnce(new Response("", { status: 503 }))
      .mockResolvedValue(new Response("{}"));
    vi.stubGlobal("fetch", fetchMock);

    despertarApi();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(2_000);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(4_000);
    expect(fetchMock).toHaveBeenCalledTimes(3);

    // A API respondeu por si: não há mais o que insistir.
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("desiste depois de insistir, sem tentar para sempre", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async () => {
      throw new Error("rede indisponível");
    });
    vi.stubGlobal("fetch", fetchMock);

    expect(() => despertarApi()).not.toThrow();
    await vi.runAllTimersAsync();

    expect(fetchMock).toHaveBeenCalledTimes(6);
  });

  it("engole a falha: acordar o serviço não pode derrubar a tela", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async () => {
      throw new Error("rede indisponível");
    });
    vi.stubGlobal("fetch", fetchMock);

    expect(() => despertarApi()).not.toThrow();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("não tenta nada com o dispositivo offline", () => {
    vi.stubGlobal("navigator", { onLine: false });
    const fetchMock = vi.fn(async () => new Response("{}"));
    vi.stubGlobal("fetch", fetchMock);

    despertarApi();

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("para de insistir se o dispositivo perder a conexão no meio", async () => {
    vi.useFakeTimers();
    const navegador = { onLine: true };
    vi.stubGlobal("navigator", navegador);
    const fetchMock = vi.fn(async () => new Response("", { status: 502 }));
    vi.stubGlobal("fetch", fetchMock);

    despertarApi();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    navegador.onLine = false;
    await vi.runAllTimersAsync();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
