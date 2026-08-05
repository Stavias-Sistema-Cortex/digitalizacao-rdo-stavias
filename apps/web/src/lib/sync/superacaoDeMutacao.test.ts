import { describe, expect, it } from "vitest";

import type { OutboxMutationRecord } from "../db/db.types";
import {
  foiSubstituida,
  vaiAdiantarReenviar,
} from "./superacaoDeMutacao";

function mutacao(
  id: string,
  status: string,
  extras: Record<string, unknown> = {},
): OutboxMutationRecord {
  return {
    clientMutationId: id,
    entidadeTipo: "EQUIPE",
    entidadeId: "equipe-1",
    operacao: "ARQUIVAR_EQUIPE",
    baseVersao: 6,
    payload: {},
    status,
    tentativas: 1,
    ultimaTentativaEm: "2026-08-05T12:00:00.000Z",
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-08-05T12:00:00.000Z",
    updatedAt: "2026-08-05T12:00:00.000Z",
    blockedReason: null,
    ...extras,
  } as OutboxMutationRecord;
}

describe("substituta só conta se estiver viva", () => {
  /*
   * O defeito medido em campo: 246 recusas sobreviveram ao descarte em lote
   * porque cada uma apontava para outra igualmente recusada. A regra perguntava
   * se existia substituta, não se ela ainda respondia por alguma coisa — então
   * duas mortas se sustentavam e nenhuma tinha saída. O descarte pulava as
   * duas, o reenvio devolvia a mesma recusa, e o número na tela não baixava.
   */
  it("não considera substituída quem só tem sucessora igualmente morta", () => {
    const original = mutacao("m-1", "REJECTED");
    const sucessora = mutacao("m-2", "REJECTED", { causationId: "m-1" });

    expect(foiSubstituida(original, [original, sucessora])).toBe(false);
  });

  it("continua respeitando a sucessora que ainda vai subir", () => {
    const original = mutacao("m-1", "REJECTED");
    const sucessora = mutacao("m-2", "PENDING", { causationId: "m-1" });

    expect(foiSubstituida(original, [original, sucessora])).toBe(true);
  });

  /*
   * Ausente conta como substituída, e de propósito: a substituta que aplicou
   * some da fila — pela poda, ou porque o ciclo a resolveu — e a marca fica
   * apontando para ela. Exigir presença faria toda original cuja substituta deu
   * certo ressuscitar como pendência, que é o inverso do que aconteceu.
   */
  it("aceita a marca cuja substituta já saiu da fila por ter subido", () => {
    const original = mutacao("m-1", "REJECTED", {
      blockedReason: "SUPERSEDED_BY:m-9",
    });

    expect(foiSubstituida(original, [original])).toBe(true);
  });

  it("recusa a marca cuja substituta está ali e morta", () => {
    const original = mutacao("m-1", "REJECTED", {
      blockedReason: "SUPERSEDED_BY:m-9",
    });
    const substituta = mutacao("m-9", "REJECTED");

    expect(foiSubstituida(original, [original, substituta])).toBe(false);
  });

  it("aceita a marca quando a substituta nomeada está viva", () => {
    const original = mutacao("m-1", "REJECTED", {
      blockedReason: "SUPERSEDED_BY:m-9",
    });
    const substituta = mutacao("m-9", "PENDING");

    expect(foiSubstituida(original, [original, substituta])).toBe(true);
  });
});

describe("o reenvio só vale onde pode mudar o desfecho", () => {
  /*
   * O laço que prendia a tela: 345 reenviados, 345 recusados, mesmo número de
   * volta. O reenvio troca a identidade da mutação e preserva `baseVersao` —
   * que é exatamente o que o servidor recusou.
   */
  it("não adianta reenviar recusa por versão", () => {
    const recusada = mutacao("m-1", "REJECTED", {
      lastSafeCode: "VERSION_CONFLICT",
    });

    expect(vaiAdiantarReenviar(recusada, [recusada])).toBe(false);
  });

  it("não adianta reenviar falha local, que nunca foi opinião do servidor", () => {
    const recusada = mutacao("m-1", "REJECTED", {
      lastSafeCode: "LOCAL_RESULT_APPLY_INVALID",
    });

    expect(vaiAdiantarReenviar(recusada, [recusada])).toBe(false);
  });

  /*
   * A metade que justifica o botão continuar existindo: recusa por regra de
   * negócio que pode ter mudado. Identidade nova faz o servidor julgar de novo
   * em vez de repetir o veredito arquivado.
   */
  it("adianta reenviar recusa de regra do servidor", () => {
    const recusada = mutacao("m-1", "REJECTED", {
      lastSafeCode: "VALIDATION_OR_AUTHORIZATION",
    });

    expect(vaiAdiantarReenviar(recusada, [recusada])).toBe(true);
  });

  it("não reenvia quem já tem substituta viva a caminho", () => {
    const original = mutacao("m-1", "REJECTED", {
      lastSafeCode: "VALIDATION_OR_AUTHORIZATION",
    });
    const substituta = mutacao("m-2", "PENDING", { causationId: "m-1" });

    expect(vaiAdiantarReenviar(original, [original, substituta])).toBe(false);
  });

  it("não reenvia o que não está em revisão", () => {
    const pendente = mutacao("m-1", "PENDING");

    expect(vaiAdiantarReenviar(pendente, [pendente])).toBe(false);
  });
});
