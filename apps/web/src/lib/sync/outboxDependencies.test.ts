import { describe, expect, it } from "vitest";

import type { OutboxMutationRecord } from "../db/db.types";
import {
  analyzeOutboxDependencies,
  explicarPendenciasQueNaoSobem,
  selectReadyOutboxMutations,
} from "./outboxDependencies";

function mutation(
  id: string,
  status: OutboxMutationRecord["status"],
  dependencies: string[] = [],
  entityId = id,
): OutboxMutationRecord {
  return {
    clientMutationId: id,
    entidadeTipo: "MENSAGEM",
    entidadeId: entityId,
    operacao: "CRIAR_MENSAGEM",
    baseVersao: null,
    payload: {},
    status,
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: `2026-07-14T12:00:0${id.length}.000Z`,
    updatedAt: "2026-07-14T12:00:00.000Z",
    transport: "SYNC_PUSH",
    dependsOnMutationIds: dependencies,
    correlationId: "conversation-1",
  };
}

function superseded(
  id: string,
  replacementId: string,
  entityId: string,
): OutboxMutationRecord {
  const original = canonicalRdoCreate(id, "REJECTED", entityId);
  original.blockedReason = `SUPERSEDED_BY:${replacementId}`;
  original.lastSafeCode = "SUPERSEDED_BY_LOCAL_EDIT";
  return original;
}

function canonicalRdoCreate(
  id: string,
  status: OutboxMutationRecord["status"],
  entityId: string,
  causationId?: string,
): OutboxMutationRecord {
  return {
    ...mutation(id, status, [], entityId),
    schemaVersion: 13,
    entidadeTipo: "RDO",
    operacao: "CRIAR_RDO",
    operation: "CREATE",
    transport: "SYNC_PUSH",
    causationId,
  } as OutboxMutationRecord;
}

describe("selectReadyOutboxMutations", () => {
  it("keeps a row in backoff out of the ready batch without blocking a new row", () => {
    const delayed = mutation("delayed", "PENDING");
    const independent = mutation("independent", "PENDING");
    delayed.nextAttemptAt = "2026-07-22T12:00:10.000Z";

    expect(
      selectReadyOutboxMutations(
        [delayed, independent],
        10,
        Date.parse("2026-07-22T12:00:05.000Z"),
      ).map((item) => item.clientMutationId),
    ).toEqual(["independent"]);
  });

  it("blocks a message until every attachment upload is synced", () => {
    const upload = mutation("upload-1", "PENDING");
    upload.transport = "OBJECT_UPLOAD";
    const message = mutation("message-1", "PENDING", ["upload-1"]);

    expect(selectReadyOutboxMutations([message, upload], 100))
      .toEqual([]);

    upload.status = "SYNCED";
    expect(selectReadyOutboxMutations([message, upload], 100))
      .toEqual([message]);
  });

  /*
   * O bloqueio aqui é real e continua valendo: a mensagem espera o anexo que
   * ainda está na fila, e mandá-la antes criaria mensagem apontando para um
   * arquivo que o servidor não tem. Antes esta prova usava uma dependência
   * ausente, que hoje não bloqueia mais — ausente quer dizer resolvida, e o que
   * segura é dependência viva e ainda não aplicada.
   */
  it("keeps independent mutations moving around a blocked dependency", () => {
    const upload = mutation("upload-1", "PENDING");
    upload.transport = "OBJECT_UPLOAD";
    const blocked = mutation("message-1", "PENDING", ["upload-1"]);
    const independent = mutation("rdo-1", "PENDING");
    independent.entidadeTipo = "RDO";
    independent.operacao = "CRIAR_RDO";

    expect(
      selectReadyOutboxMutations([upload, blocked, independent], 100)
        .map((item) => item.clientMutationId),
    ).toEqual(["rdo-1"]);
  });

  it("treats legacy records without dependency fields as sync push", () => {
    const legacy = mutation("legacy-1", "PENDING");
    delete legacy.transport;
    delete legacy.dependsOnMutationIds;

    expect(selectReadyOutboxMutations([legacy], 100))
      .toEqual([legacy]);
  });

  it("never releases a mutation with a durable blocked reason", () => {
    const upload = mutation("upload-1", "SYNCED");
    upload.transport = "OBJECT_UPLOAD";
    const blocked = mutation("message-1", "PENDING", ["upload-1"]);
    blocked.blockedReason =
      "CANONICAL_UPLOAD_REFERENCE_REQUIRES_REPLACEMENT";

    expect(selectReadyOutboxMutations([blocked, upload], 100)).toEqual([]);
  });

  it("resolves an immutable superseded dependency only after its same-entity replacement syncs", () => {
    const original = superseded("create-a-1", "create-a-2", "rdo-a");
    const replacement = canonicalRdoCreate(
      "create-a-2",
      "PENDING",
      "rdo-a",
      original.clientMutationId,
    );
    const dependent = mutation("create-b", "PENDING", [original.clientMutationId], "rdo-b");
    dependent.entidadeTipo = "RDO";
    dependent.operacao = "CRIAR_RDO";
    const immutableDependent = JSON.stringify(dependent);

    expect(selectReadyOutboxMutations([original, replacement, dependent], 100))
      .toEqual([replacement]);

    replacement.status = "SYNCED";
    expect(selectReadyOutboxMutations([original, replacement, dependent], 100))
      .toEqual([dependent]);
    expect(JSON.stringify(dependent)).toBe(immutableDependent);
  });

  it("follows repeated replacements without depending on terminal aliases", () => {
    const first = superseded("create-a-1", "create-a-2", "rdo-a");
    const second = superseded("create-a-2", "create-a-3", "rdo-a");
    second.causationId = first.clientMutationId;
    const terminal = canonicalRdoCreate(
      "create-a-3",
      "SYNCED",
      "rdo-a",
      second.clientMutationId,
    );
    const dependent = mutation("create-b", "PENDING", [first.clientMutationId], "rdo-b");

    expect(selectReadyOutboxMutations([first, second, terminal, dependent], 100))
      .toEqual([dependent]);
  });

  it("does not accept a same-entity synced CREATE without the exact causal link", () => {
    const original = superseded("create-a-1", "create-a-2", "rdo-a");
    const replacement = canonicalRdoCreate("create-a-2", "SYNCED", "rdo-a");
    const dependent = mutation("create-b", "PENDING", [original.clientMutationId], "rdo-b");

    expect(selectReadyOutboxMutations([original, replacement, dependent], 100))
      .toEqual([]);
    expect(analyzeOutboxDependencies([original, replacement, dependent]))
      .toMatchObject({
        invalidAliases: [
          expect.objectContaining({
            mutationId: dependent.clientMutationId,
            aliasId: original.clientMutationId,
            reason: "CAUSATION_MISMATCH",
          }),
        ],
      });
  });

  it("blocks direct and repeated chains with wrong cause, operation, or transport", () => {
    const wrongCause = superseded("cause-1", "cause-2", "rdo-cause");
    const wrongCauseTarget = canonicalRdoCreate(
      "cause-2",
      "SYNCED",
      "rdo-cause",
      "some-other-mutation",
    );
    const wrongOperation = superseded("operation-1", "operation-2", "rdo-operation");
    const wrongOperationTarget = canonicalRdoCreate(
      "operation-2",
      "SYNCED",
      "rdo-operation",
      wrongOperation.clientMutationId,
    );
    wrongOperationTarget.operation = "UPDATE";
    wrongOperationTarget.operacao = "ATUALIZAR_RDO_RASCUNHO";
    const wrongTransport = superseded("transport-1", "transport-2", "rdo-transport");
    const wrongTransportTarget = canonicalRdoCreate(
      "transport-2",
      "SYNCED",
      "rdo-transport",
      wrongTransport.clientMutationId,
    );
    wrongTransportTarget.transport = "OBJECT_UPLOAD";
    const chainFirst = superseded("chain-1", "chain-2", "rdo-chain");
    const chainSecond = superseded("chain-2", "chain-3", "rdo-chain");
    chainSecond.causationId = chainFirst.clientMutationId;
    const chainTerminal = canonicalRdoCreate(
      "chain-3",
      "SYNCED",
      "rdo-chain",
      chainFirst.clientMutationId,
    );
    const dependents = [
      mutation("depends-cause", "PENDING", [wrongCause.clientMutationId]),
      mutation("depends-operation", "PENDING", [wrongOperation.clientMutationId]),
      mutation("depends-transport", "PENDING", [wrongTransport.clientMutationId]),
      mutation("depends-chain", "PENDING", [chainFirst.clientMutationId]),
    ];

    const all = [
      wrongCause,
      wrongCauseTarget,
      wrongOperation,
      wrongOperationTarget,
      wrongTransport,
      wrongTransportTarget,
      chainFirst,
      chainSecond,
      chainTerminal,
      ...dependents,
    ];
    expect(selectReadyOutboxMutations(all, 100)).toEqual([]);
    expect(analyzeOutboxDependencies(all).invalidAliases).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ reason: "CAUSATION_MISMATCH" }),
        expect.objectContaining({ reason: "NON_CANONICAL_CREATE" }),
      ]),
    );
  });

  it("blocks missing, cyclic, cross-entity, and non-terminal alias corruption", () => {
    const missing = superseded("missing-1", "gone", "rdo-a");
    const cycleA = superseded("cycle-a", "cycle-b", "rdo-cycle");
    const cycleB = superseded("cycle-b", "cycle-a", "rdo-cycle");
    cycleA.causationId = cycleB.clientMutationId;
    cycleB.causationId = cycleA.clientMutationId;
    const cross = superseded("cross-1", "cross-2", "rdo-a");
    const crossTarget = mutation("cross-2", "SYNCED", [], "rdo-other");
    const nonTerminal = mutation("pending-alias", "PENDING", [], "rdo-a");
    nonTerminal.blockedReason = "SUPERSEDED_BY:cross-2";
    const dependents = [
      mutation("depends-missing", "PENDING", ["missing-1"]),
      mutation("depends-cycle", "PENDING", ["cycle-a"]),
      mutation("depends-cross", "PENDING", ["cross-1"]),
      mutation("depends-pending-alias", "PENDING", ["pending-alias"]),
    ];
    const all = [
      missing,
      cycleA,
      cycleB,
      cross,
      crossTarget,
      nonTerminal,
      ...dependents,
    ];

    expect(selectReadyOutboxMutations(all, 100)).toEqual([]);
    expect(analyzeOutboxDependencies(all)).toMatchObject({
      cycles: expect.arrayContaining(["cycle-a", "cycle-b"]),
      missingDependencies: expect.arrayContaining([
        { mutationId: "depends-missing", dependencyId: "gone" },
      ]),
      invalidAliases: expect.arrayContaining([
        expect.objectContaining({ mutationId: "depends-cross" }),
      ]),
    });
  });
});

describe("analyzeOutboxDependencies", () => {
  /*
   * O ciclo continua barrado — duas mutações que esperam uma pela outra nunca
   * ficam prontas, e enviar qualquer uma quebraria a ordem que elas declaram.
   *
   * A dependência ausente, não. A outbox é fila, não arquivo: quem aplica sai
   * dela. Uma dependência que não está mais ali já teve desfecho, e prender o
   * filho troca uma recusa visível do servidor por um congelamento mudo. Foi
   * assim que três adições de membro ficaram paradas depois de uma limpeza de
   * fila remover as mutações de que dependiam, com a tela prometendo que o
   * sistema tentaria sincronizar enquanto o motor nem as considerava.
   */
  it("reporta a órfã mas deixa ela subir, e continua barrando o ciclo", () => {
    const first = mutation("first", "PENDING", ["second"]);
    const second = mutation("second", "PENDING", ["first"]);
    const orfa = mutation("orfa", "PENDING", ["gone"]);

    const analysis = analyzeOutboxDependencies([first, second, orfa]);

    expect(analysis.cycles).toEqual(
      expect.arrayContaining(["first", "second"]),
    );
    // A análise continua dizendo quem ficou órfão: o que saiu foi o veto.
    expect(analysis.missingDependencies).toEqual([
      { mutationId: "orfa", dependencyId: "gone" },
    ]);
    expect(
      selectReadyOutboxMutations([first, second, orfa], 100)
        .map((item) => item.clientMutationId),
    ).toEqual(["orfa"]);
  });
});

/*
 * O silêncio que custou uma investigação inteira: um RDO com a fila local
 * marcando 1, a tarja prometendo envio automático, e nenhuma requisição de
 * push na aba de rede — porque `selectReadyOutboxMutations` descartava a linha
 * antes de existir requisição alguma. O motivo estava só no IndexedDB.
 */
describe("explicarPendenciasQueNaoSobem", () => {
  it("nomeia a linha bloqueada que a fila conta como pendente", () => {
    const bloqueada = mutation("bloqueada", "PENDING");
    bloqueada.blockedReason = "RDO_CREATION_CONTEXT_REQUIRED";

    expect(
      selectReadyOutboxMutations([bloqueada], 100),
    ).toHaveLength(0);
    expect(explicarPendenciasQueNaoSobem([bloqueada])).toEqual([
      {
        mutationId: "bloqueada",
        entidadeTipo: "MENSAGEM",
        entidadeId: "bloqueada",
        operacao: "CRIAR_MENSAGEM",
        motivo: "BLOQUEADA",
        detalhe: "RDO_CREATION_CONTEXT_REQUIRED",
      },
    ]);
  });

  it("nomeia o ciclo e a dependência que ainda não subiu", () => {
    const first = mutation("first", "PENDING", ["second"]);
    const second = mutation("second", "PENDING", ["first"]);
    const presa = mutation("presa", "PENDING", ["parada"]);
    const parada = mutation("parada", "ERROR");

    expect(
      explicarPendenciasQueNaoSobem([first, second, presa, parada])
        .map((item) => [item.mutationId, item.motivo]),
    ).toEqual([
      ["first", "CICLO"],
      ["presa", "DEPENDENCIA_PENDENTE"],
      ["second", "CICLO"],
    ]);
  });

  /*
   * Espera que vence sozinha não é impasse. Chamar de travado o que volta na
   * próxima janela gastaria o alarme justamente no caso que não precisa dele.
   */
  it("não chama de travada a espera que se resolve sozinha", () => {
    const agora = Date.parse("2026-08-07T12:00:00.000Z");
    const emEspera = mutation("em-espera", "PENDING");
    emEspera.nextAttemptAt = "2026-08-07T12:00:30.000Z";
    const anexo = mutation("anexo", "PENDING");
    anexo.transport = "OBJECT_UPLOAD";

    expect(
      selectReadyOutboxMutations([emEspera, anexo], 100, agora),
    ).toHaveLength(0);
    expect(
      explicarPendenciasQueNaoSobem([emEspera, anexo], agora),
    ).toEqual([]);
  });

  it("ignora a linha pronta e a que já saiu da fila", () => {
    const pronta = mutation("pronta", "PENDING");
    const aplicada = mutation("aplicada", "SYNCED");

    expect(
      explicarPendenciasQueNaoSobem([pronta, aplicada]),
    ).toEqual([]);
  });
});
