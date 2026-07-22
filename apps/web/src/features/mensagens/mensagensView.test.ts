import { describe, expect, it } from "vitest";

import type { MensagemLocalRecord } from "../../lib/db/db.types";
import {
  buildConversationPreviews,
  buildMessageTimeline,
  hasPendingMessage,
  previewLabel,
} from "./mensagensView";

function message(
  id: string,
  conversationId: string,
  authorId: string,
  body: string | null,
  createdAt: string,
): MensagemLocalRecord {
  return {
    id,
    conversaId: conversationId,
    autorId: authorId,
    autorNome: authorId === "a" ? "Ana" : "Bruno",
    corpo: body,
    status: "ATIVA",
    clientMutationId: id,
    criadaNoClienteEm: createdAt,
    criadaEm: createdAt,
    editadaEm: null,
    deletadaEm: null,
    versaoEntidade: 0,
    syncStatus: "SINCRONIZADO",
    ultimoErro: null,
    updatedAt: createdAt,
  };
}

describe("mensagensView", () => {
  it("agrupa o histórico por data e repete autor somente quando ele muda", () => {
    const entries = buildMessageTimeline([
      message("m1", "c1", "a", "Primeira", "2026-07-14T12:00:00.000Z"),
      message("m2", "c1", "a", "Segunda", "2026-07-14T12:05:00.000Z"),
      message("m3", "c1", "b", "Terceira", "2026-07-14T12:06:00.000Z"),
      message("m4", "c1", "b", "Quarta", "2026-07-15T12:00:00.000Z"),
    ]);

    expect(entries.map((entry) => entry.kind)).toEqual([
      "date", "message", "message", "message", "date", "message",
    ]);
    expect(entries.filter((entry) => entry.kind === "message")
      .map((entry) => entry.startsRun)).toEqual([true, false, true, true]);
  });

  it("abre novo run quando o mesmo autor volta depois de 15 minutos", () => {
    const entries = buildMessageTimeline([
      message("m1", "c1", "a", "Primeira", "2026-07-14T12:00:00.000Z"),
      message("m2", "c1", "a", "Logo depois", "2026-07-14T12:10:00.000Z"),
      message("m3", "c1", "a", "Bem depois", "2026-07-14T12:40:00.000Z"),
    ]);

    const runs = entries
      .filter((entry) => entry.kind === "message")
      .map((entry) => (entry.kind === "message" ? entry.startsRun : false));

    expect(runs).toEqual([true, false, true]);
  });

  it("usa somente a última mensagem real como prévia da conversa", () => {
    const previews = buildConversationPreviews([
      message("m1", "c1", "a", "Anterior", "2026-07-14T12:00:00.000Z"),
      message("m2", "c1", "b", "Atualização da ponte", "2026-07-14T13:00:00.000Z"),
      message("m3", "c2", "a", null, "2026-07-14T14:00:00.000Z"),
    ], new Set(["m3"]));

    expect(previews.c1).toMatchObject({
      messageId: "m2",
      text: "Atualização da ponte",
      authorName: "Bruno",
    });
    expect(previews.c2.text).toBe("Documento anexado");
  });

  it("guarda o autor da prévia para identificar mensagens próprias", () => {
    const previews = buildConversationPreviews(
      [message("m1", "c1", "a", "Combinado", "2026-07-14T12:00:00.000Z")],
      new Set(),
    );

    expect(previews.c1.authorId).toBe("a");
    expect(previewLabel(previews.c1, "a", "Conversa")).toBe("Você: Combinado");
    expect(previewLabel(previews.c1, "b", "Conversa")).toBe("Combinado");
    expect(previewLabel(undefined, "a", "Conversa")).toBe("Conversa");
  });

  it("sinaliza prévia ainda não sincronizada", () => {
    const previews = buildConversationPreviews(
      [message("m1", "c1", "a", "Enviando", "2026-07-14T12:00:00.000Z")],
      new Set(),
    );

    expect(hasPendingMessage(previews.c1)).toBe(false);
    expect(hasPendingMessage({ ...previews.c1, syncStatus: "NA_FILA" })).toBe(true);
    expect(hasPendingMessage({ ...previews.c1, syncStatus: "FALHOU" })).toBe(true);
    expect(hasPendingMessage(undefined)).toBe(false);
  });
});
