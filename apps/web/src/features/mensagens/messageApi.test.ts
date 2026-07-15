import { beforeEach, describe, expect, it, vi } from "vitest";

const { apiFetch } = vi.hoisted(() => ({
  apiFetch: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", () => ({
  apiFetch,
  readResponseBody: async (response: Response) => {
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  },
  responseErrorMessage: (body: unknown, status: number) =>
    typeof body === "object" && body && "message" in body
      ? String((body as { message: unknown }).message)
      : `HTTP ${status}`,
}));

import {
  downloadMessageAttachment,
  fetchConversations,
  fetchMessages,
  MessageApiError,
  uploadMessageAttachment,
} from "./messageApi";

beforeEach(() => apiFetch.mockReset());

describe("messageApi", () => {
  it("envia anexo por PUT multipart sem fixar Content-Type", async () => {
    apiFetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "attachment-1",
          mensagemId: "message-1",
          status: "DISPONIVEL",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    const blob = new Blob(["arquivo"], { type: "text/plain" });

    await uploadMessageAttachment(
      "message-1",
      "attachment-1",
      blob,
      "evidencia.txt",
    );

    expect(apiFetch).toHaveBeenCalledOnce();
    const [path, options] = apiFetch.mock.calls[0] as [
      string,
      RequestInit,
    ];
    expect(path).toBe(
      "/mensagens/message-1/anexos/attachment-1/conteudo",
    );
    expect(options.method).toBe("PUT");
    expect(options.body).toBeInstanceOf(FormData);
    expect(options.headers).toBeUndefined();
    expect((options.body as FormData).get("arquivo")).toBeInstanceOf(Blob);
  });

  it("preserva status HTTP para classificação permanente", async () => {
    apiFetch.mockResolvedValue(
      new Response(JSON.stringify({ message: "Acesso negado" }), {
        status: 403,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(
      uploadMessageAttachment(
        "message-1",
        "attachment-1",
        new Blob(["x"]),
        "x.txt",
      ),
    ).rejects.toMatchObject<MessageApiError>({
      status: 403,
      message: "Acesso negado",
    });
  });

  it("codifica filtros reais da lista de conversas", async () => {
    apiFetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [],
          page: 0,
          size: 20,
          totalElements: 0,
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );

    await fetchConversations({ texto: "frente norte", obraId: "obra/1" });

    expect(apiFetch.mock.calls[0][0]).toBe(
      "/conversas?texto=frente+norte&obraId=obra%2F1",
    );
  });

  it("envia o cursor real ao carregar mensagens mais antigas", async () => {
    apiFetch.mockResolvedValue(
      new Response(
        JSON.stringify({ items: [], proximoCursor: null, hasMore: false }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );

    await fetchMessages("conversation-1", {
      limit: 30,
      antesDeClienteEm: "2026-07-15T12:00:00Z",
      antesDeServidorEm: "2026-07-15T12:00:01Z",
      antesDeId: "message/1",
    });

    expect(apiFetch.mock.calls[0][0]).toBe(
      "/conversas/conversation-1/mensagens?limit=30&antesDeClienteEm=2026-07-15T12%3A00%3A00Z&antesDeServidorEm=2026-07-15T12%3A00%3A01Z&antesDeId=message%2F1",
    );
  });

  it("baixa anexo pelo endpoint autenticado", async () => {
    apiFetch.mockResolvedValue(
      new Response(new Blob(["conteúdo"], { type: "text/plain" }), {
        status: 200,
        headers: { "Content-Type": "text/plain" },
      }),
    );

    const downloaded = await downloadMessageAttachment(
      "message-1",
      "attachment-1",
    );

    expect(apiFetch.mock.calls[0][0]).toBe(
      "/mensagens/message-1/anexos/attachment-1/conteudo",
    );
    expect(await downloaded.text()).toBe("conteúdo");
  });
});
