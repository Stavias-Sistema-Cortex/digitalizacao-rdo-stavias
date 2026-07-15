import { describe, expect, it } from "vitest";

import type {
  LocalConversationRecord,
  LocalMessageRecord,
} from "../../lib/db/db.types";
import {
  conversationDisplayName,
  groupMessagesByDay,
  messageDeliveryLabel,
  participantInitials,
} from "./messageViewModel";

function conversation(
  overrides: Partial<LocalConversationRecord> = {},
): LocalConversationRecord {
  return {
    id: "conversation-1",
    tipo: "OBRA",
    titulo: null,
    obraId: "obra-1",
    obraNome: "Contorno Norte",
    equipeId: null,
    equipeNome: null,
    criadoPor: "user-1",
    status: "ATIVA",
    ultimaAtividadeEm: "2026-07-15T14:00:00Z",
    ultimaMensagemId: null,
    ultimaMensagemPrevia: null,
    ultimaMensagemEm: null,
    naoLidas: 0,
    criadoEm: "2026-07-15T12:00:00Z",
    atualizadoEm: "2026-07-15T14:00:00Z",
    versaoEntidade: 1,
    ...overrides,
  };
}

function message(
  id: string,
  sentAt: string,
  overrides: Partial<LocalMessageRecord> = {},
): LocalMessageRecord {
  return {
    id,
    conversaId: "conversation-1",
    remetenteId: "user-1",
    remetenteNome: "Ana",
    clientMessageId: `client-${id}`,
    texto: "Atualização",
    estado: "ENVIADA",
    enviadaClienteEm: sentAt,
    criadaServidorEm: sentAt,
    atualizadoEm: sentAt,
    versaoEntidade: 1,
    recibos: [],
    syncStatus: "SENT",
    ultimoErro: null,
    ...overrides,
  };
}

describe("messageViewModel", () => {
  it("usa contexto real para nomear conversas sem título", () => {
    expect(conversationDisplayName(conversation())).toBe("Contorno Norte");
    expect(
      conversationDisplayName(
        conversation({ tipo: "EQUIPE", equipeNome: "Drenagem" }),
      ),
    ).toBe("Drenagem");
    expect(
      conversationDisplayName(conversation({ titulo: "Decisão de campo" })),
    ).toBe("Decisão de campo");
  });

  it("gera iniciais legíveis sem inventar uma pessoa", () => {
    expect(participantInitials("Ana Beatriz Costa")).toBe("AC");
    expect(participantInitials("Equipe Drenagem")).toBe("ED");
    expect(participantInitials(null)).toBe("—");
  });

  it("separa o histórico pela data local mantendo a ordem", () => {
    const groups = groupMessagesByDay([
      message("m1", "2026-07-14T12:00:00Z"),
      message("m2", "2026-07-15T12:00:00Z"),
    ]);

    expect(groups).toHaveLength(2);
    expect(groups.flatMap((group) => group.messages.map((item) => item.id)))
      .toEqual(["m1", "m2"]);
  });

  it("expõe pendência, falha, entrega e leitura sem esconder o estado local", () => {
    expect(messageDeliveryLabel(message("m1", "2026-07-15T12:00:00Z", {
      syncStatus: "PENDING",
    }))).toBe("Pendente");
    expect(messageDeliveryLabel(message("m2", "2026-07-15T12:00:00Z", {
      syncStatus: "ERROR",
    }))).toBe("Falha no envio");
    expect(messageDeliveryLabel(message("m3", "2026-07-15T12:00:00Z", {
      recibos: [{
        id: "receipt-1",
        mensagemId: "m3",
        colaboradorId: "user-2",
        entregueEm: "2026-07-15T12:01:00Z",
        lidaEm: "2026-07-15T12:02:00Z",
      }],
    }))).toBe("Lida");
  });
});
