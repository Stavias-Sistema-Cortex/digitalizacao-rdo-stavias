import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Reparo é manutenção da fila; o ciclo é o trabalho.
 *
 * <p>Uma linha podre na outbox — gravada por uma versão antiga do app, ou
 * corrompida — fazia o reparo dela estourar e derrubava o ciclo inteiro: o
 * aparelho parava de enviar e de receber TUDO, para sempre, por causa de uma
 * linha. E só aquele aparelho: nas outras máquinas tudo seguia normal, que é a
 * assinatura do defeito impossível de reproduzir à distância.
 *
 * <p>O que continua derrubando o ciclo, de propósito: sessão trocada no meio
 * de um passo. Aí quem morre é o ciclo mesmo, antes de escrever no banco de
 * outra conta.
 */

const mocks = vi.hoisted(() => ({
  currentFingerprint: "session-a",
  capture: vi.fn(() => ({
    fingerprint: mocks.currentFingerprint,
    userId: "user-a",
  })),
  assert: vi.fn((guard: { fingerprint: string }) => {
    if (guard.fingerprint !== mocks.currentFingerprint) {
      throw new Error("A sessão mudou durante a sincronização.");
    }
  }),
  contar: vi.fn(async () => 1),
  updateSyncState: vi.fn(async () => undefined),
  recover: vi.fn(async () => undefined),
  reidentificarObras: vi.fn(async () => 0),
  repairObra: vi.fn(async () => 0),
  repairMaoObra: vi.fn(async () => 0),
  hydrateRdo: vi.fn(async () => 0),
  releaseRdoUpdate: vi.fn(async () => 0),
  repairRdo: vi.fn(async () => 0),
  recoverRejectedRdo: vi.fn(async () => 0),
  recoverErroredWorkforceRdo: vi.fn(async () => 0),
  queueErroredRetry: vi.fn(async () => 0),
  recoverRejectedGeometry: vi.fn(async () => 0),
  recoverRejectedArchivedObra: vi.fn(async () => 0),
  resolveUploads: vi.fn(async () => 0),
  recoverConflicts: vi.fn(async () => 0),
  desamarrar: vi.fn(async () => 0),
  podar: vi.fn(async () => 0),
  ensureDevice: vi.fn(async () => "device"),
  uploads: vi.fn(async () => ({ pushed: 0, applied: 0, errors: 0 })),
  push: vi.fn(async () => ({
    pushed: 0,
    applied: 0,
    errors: 0,
    retryableErrors: 0,
    conflicts: 0,
    reconciledReplacementByOriginalId: new Map(),
    appliedMutationIds: [],
    handledMutationIds: [],
    errorMutationIds: [],
  })),
  pull: vi.fn(async () => ({
    pulled: 0,
    messagingConversationIds: [],
    pendente: false,
  })),
  refresh: vi.fn(async () => undefined),
  ack: vi.fn(async () => 0),
  runWithLease: vi.fn(
    async (
      _guard: { fingerprint: string },
      task: (lease: {
        ownerToken: string;
        assertOwned(): Promise<void>;
      }) => Promise<unknown>,
    ) =>
      task({
        ownerToken: "owner-test",
        assertOwned: async () => undefined,
      }),
  ),
}));

vi.mock("./syncSession", () => ({
  captureOnlineSyncSession: mocks.capture,
  assertSyncSession: mocks.assert,
}));
vi.mock("./syncExecutionLease", () => ({
  runWithSyncExecutionLease: mocks.runWithLease,
}));
vi.mock("../db/syncStateRepository", () => ({
  updateSyncState: mocks.updateSyncState,
}));
vi.mock("./syncStorage", () => ({
  contarMutacoesDaOutbox: mocks.contar,
  desamarrarCitacoesFantasmas: mocks.desamarrar,
  podarMutacoesJaAplicadas: mocks.podar,
  queueErroredMutationsForRetry: mocks.queueErroredRetry,
  recoverInterruptedMutations: mocks.recover,
  reidentificarObrasInexistentesForSync: mocks.reidentificarObras,
  repairMissingMaoObraReferencesForSync: mocks.repairMaoObra,
  repairMissingObraReferencesForSync: mocks.repairObra,
  resolveCanonicalUploadReplacements: mocks.resolveUploads,
  recoverCanonicalConflictReconciliations: mocks.recoverConflicts,
  recoverRejectedGeometryMutationsForSync: mocks.recoverRejectedGeometry,
  recoverRejectedArchivedObraMutationsForSync:
    mocks.recoverRejectedArchivedObra,
}));
vi.mock("../db/localRdoService", () => ({
  hydrateBlockedRdoCreationContextsForSync: mocks.hydrateRdo,
  releaseBlockedRdoUpdatesForSync: mocks.releaseRdoUpdate,
  repairRdoCreateMutationsForSync: mocks.repairRdo,
  recoverErroredWorkforceRdoMutationsForSync:
    mocks.recoverErroredWorkforceRdo,
  recoverRejectedRdoMutationsForSync: mocks.recoverRejectedRdo,
}));
vi.mock("./registerDevice", () => ({
  ensureRegisteredDevice: mocks.ensureDevice,
}));
vi.mock("../../features/mensagens/objectUploadSync", () => ({
  processObjectUploads: mocks.uploads,
}));
vi.mock("./pushOutbox", () => ({ pushOutbox: mocks.push }));
vi.mock("./pullEvents", () => ({ pullEvents: mocks.pull }));
vi.mock("../../features/mensagens/mensagensHydration", () => ({
  refreshMessagingAfterPull: mocks.refresh,
}));
vi.mock("./ackCursor", () => ({ acknowledgeCurrentCursor: mocks.ack }));
vi.mock("../../features/auth/authSession", () => ({
  hasOnlineSession: () => true,
}));
vi.stubGlobal("navigator", { onLine: true });
vi.stubGlobal("window", new EventTarget());

import { syncNow } from "./syncEngine";

describe("um reparo podre não derruba o ciclo", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.currentFingerprint = "session-a";
    mocks.contar.mockResolvedValue(1);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
  });

  it("pula o reparo que falhou e ainda envia e recebe", async () => {
    mocks.repairObra.mockRejectedValueOnce(
      new Error("linha gravada por versão antiga do app"),
    );

    const resumo = await syncNow();

    expect(mocks.push).toHaveBeenCalledTimes(1);
    expect(mocks.pull).toHaveBeenCalledTimes(1);
    // Os reparos seguintes ao que falhou continuam acontecendo.
    expect(mocks.queueErroredRetry).toHaveBeenCalledTimes(1);
    expect(resumo.reparosFalharam).toEqual(["referências de obra"]);
  });

  /*
   * A mensageria depois do pull ficou de fora do isolamento quando os reparos
   * entraram, e era o pior lugar para ficar: o pull já tinha gravado tudo, e
   * a falha derrubava o ciclo antes de confirmar o cursor e — o que mais dói —
   * antes de avisar as telas de que havia dado novo. O aparelho ficava com o
   * conteúdo em mãos e mostrando o retrato velho.
   */
  it("a mensageria que falha depois do pull não leva o ciclo junto", async () => {
    mocks.refresh.mockRejectedValueOnce(new Error("mensageria fora do ar"));

    const resumo = await syncNow();

    expect(mocks.pull).toHaveBeenCalledTimes(1);
    expect(mocks.ack).toHaveBeenCalledTimes(1);
    expect(resumo.reparosFalharam).toEqual(["mensagens após o pull"]);
  });

  it("um ciclo saudável não anota falha nenhuma", async () => {
    const resumo = await syncNow();

    expect(resumo.reparosFalharam).toEqual([]);
  });

  /*
   * A exceção deliberada: a sessão que trocou no meio do passo derruba o
   * ciclo, como sempre derrubou. O isolamento não pode virar uma porta para
   * escrever no banco de outra conta.
   */
  it("a sessão trocada dentro do reparo continua derrubando o ciclo", async () => {
    mocks.repairObra.mockImplementationOnce(async () => {
      mocks.currentFingerprint = "session-b";
      throw new Error("qualquer falha no meio");
    });

    await expect(syncNow()).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(mocks.push).not.toHaveBeenCalled();
  });
});

describe("a fila vazia dispensa a manutenção da fila", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.currentFingerprint = "session-a";
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
  });

  it("pula os reparos e vai direto ao envio e ao recebimento", async () => {
    mocks.contar.mockResolvedValue(0);

    await syncNow();

    expect(mocks.recover).not.toHaveBeenCalled();
    expect(mocks.repairObra).not.toHaveBeenCalled();
    expect(mocks.queueErroredRetry).not.toHaveBeenCalled();
    expect(mocks.push).toHaveBeenCalledTimes(1);
    expect(mocks.pull).toHaveBeenCalledTimes(1);
  });

  it("com linhas na fila, a manutenção roda como sempre", async () => {
    mocks.contar.mockResolvedValue(3);

    await syncNow();

    expect(mocks.recover).toHaveBeenCalledTimes(1);
    expect(mocks.queueErroredRetry).toHaveBeenCalledTimes(1);
  });
});
