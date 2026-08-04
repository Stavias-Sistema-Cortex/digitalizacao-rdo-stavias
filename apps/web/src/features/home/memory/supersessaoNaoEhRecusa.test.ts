import { describe, expect, it } from "vitest";

import type { OperationalEventRecord } from "../../../lib/db/db.types";
import {
  localEventToSearchDocument,
  memoryCoverage,
  memoryStatusLabel,
} from "./memorySearchDocument";

const USUARIO_ID = "00000000-0000-4000-8000-000000000801";
const OBRA_ID = "00000000-0000-4000-8000-000000000802";
const ESCOPO = "escopo-a";

function evento(
  overrides: Partial<OperationalEventRecord> = {},
): OperationalEventRecord {
  return {
    id: "evento-local-1",
    type: "RDO_EDITADO",
    principalEntity: { tipo: "RDO", id: "rdo-1", nome: "Compactação Norte" },
    principalEntityKey: "RDO:rdo-1",
    relatedEntities: [],
    obraId: OBRA_ID,
    rdoId: "rdo-1",
    colaboradorId: null,
    occurredAt: "2026-08-03T09:00:00.000Z",
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: USUARIO_ID,
    responsibleUserName: "Encarregada de campo",
    payload: {},
    syncStatus: "PENDING_SYNC",
    schemaVersion: 13,
    clientMutationId: "mutacao-1",
    deviceId: "dispositivo-1",
    correlationId: "correlacao-1",
    causationId: null,
    entityVersion: 3,
    result: "PENDING",
    ...overrides,
  };
}

function documento(overrides: Partial<OperationalEventRecord>) {
  return localEventToSearchDocument(USUARIO_ID, ESCOPO, evento(overrides));
}

/**
 * Salvar de novo não é ser recusado.
 *
 * O RDO é gravado a cada campo preenchido, e cada gravação absorve a anterior:
 * a fila fica com um envelope só, que é o comportamento certo. O que estava
 * errado era o que se dizia da absorvida — `REJECTED` com `SYNC_FAILED`, num
 * aparelho que passou o dia inteiro sem rede e nunca falou com servidor
 * nenhum. Um dia normal de apontamento chegava à Memória como três falhas de
 * sincronização por RDO, pedindo revisão do que não tem o que revisar, e ainda
 * acendia a tarja de "alterações recusadas" no topo da aba.
 *
 * É a mesma classe de erro que o produto promete não cometer: o histórico
 * precisa dizer o que aconteceu, e o que aconteceu foi uma pessoa continuando
 * a preencher o próprio relatório.
 */
describe("supersessão local não é recusa", () => {
  it("descreve a escrita absorvida como substituída", () => {
    const absorvida = documento({
      result: "SUPERSEDED",
      syncStatus: "LOCAL_ONLY",
      errorCategory: "SUPERSEDED_BY_LOCAL_EDIT",
    });

    expect(absorvida.syncStatus).toBe("SUPERSEDED");
    expect(memoryStatusLabel(absorvida.syncStatus)).toBe("Substituído");
  });

  /** Sem evidência de revisão não há pendência — e aqui não há o que decidir. */
  it("não pede revisão de quem foi absorvido", () => {
    expect(
      documento({
        result: "SUPERSEDED",
        syncStatus: "LOCAL_ONLY",
        errorCategory: "SUPERSEDED_BY_LOCAL_EDIT",
      }).review,
    ).toBeNull();
  });

  /**
   * Os aparelhos que já apontaram offline carregam o estado antigo gravado. A
   * categoria do erro sempre disse a verdade, então lê-la na leitura cura o
   * histórico existente sem migração — e sem deixar quem já trabalhou
   * convivendo com falhas que nunca existiram.
   */
  it("cura o que já foi gravado como recusa antes de existir o estado próprio", () => {
    for (const categoria of [
      "SUPERSEDED_BY_LOCAL_EDIT",
      "SUPERSEDED_BY_CONFLICT_REPLACEMENT",
      "SUPERSEDED_BY_REPLACEMENT",
    ]) {
      const antigo = documento({
        result: "REJECTED",
        syncStatus: "SYNC_FAILED",
        errorCategory: categoria,
      });

      expect(antigo.syncStatus).toBe("SUPERSEDED");
      expect(antigo.review).toBeNull();
    }
  });

  /** Recusa de verdade continua sendo recusa. */
  it("preserva a recusa vinda do servidor", () => {
    const recusado = documento({
      result: "REJECTED",
      syncStatus: "SYNC_FAILED",
      errorCategory: "SERVER_VALIDATION",
    });

    expect(recusado.syncStatus).toBe("REJECTED");
    expect(recusado.review).toMatchObject({ status: "REJECTED" });
  });

  /**
   * A tarja do topo da aba lê os estados locais em conjunto. Enquanto a
   * absorção contava como recusa, um dia de campo bem-sucedido abria a Memória
   * anunciando "alterações recusadas".
   */
  it("não acende o aviso de recusa no topo da aba", () => {
    const cobertura = memoryCoverage({
      online: false,
      metadata: {
        userId: USUARIO_ID,
        scopeHash: ESCOPO,
        highWaterMark: 12,
        authorizedEventCount: 12,
        cachedEventCount: 12,
        complete: true,
        updatedAt: "2026-08-03T10:00:00.000Z",
      },
      localStatuses: ["SUPERSEDED", "SUPERSEDED", "UPDATED"],
    });

    expect(cobertura.status).not.toBe("REJECTED");
    expect(cobertura.detail).not.toContain("recusadas");
  });
});
