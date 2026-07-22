import { describe, expect, it } from "vitest";

import type { OutboxMutationRecord } from "../db/db.types";
import {
  analyzeOutboxDependencies,
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
  const original = mutation(id, "REJECTED", [], entityId);
  original.blockedReason = `SUPERSEDED_BY:${replacementId}`;
  original.lastSafeCode = "SUPERSEDED_BY_LOCAL_EDIT";
  return original;
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

  it("keeps independent mutations moving around a blocked dependency", () => {
    const blocked = mutation("message-1", "PENDING", ["upload-1"]);
    const independent = mutation("rdo-1", "PENDING");
    independent.entidadeTipo = "RDO";
    independent.operacao = "CRIAR_RDO";

    expect(selectReadyOutboxMutations([blocked, independent], 100))
      .toEqual([independent]);
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
    const replacement = mutation("create-a-2", "PENDING", [], "rdo-a");
    replacement.entidadeTipo = "RDO";
    replacement.operacao = "CRIAR_RDO";
    original.entidadeTipo = "RDO";
    original.operacao = "CRIAR_RDO";
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
    const terminal = mutation("create-a-3", "SYNCED", [], "rdo-a");
    for (const item of [first, second, terminal]) {
      item.entidadeTipo = "RDO";
      item.operacao = "CRIAR_RDO";
    }
    const dependent = mutation("create-b", "PENDING", [first.clientMutationId], "rdo-b");

    expect(selectReadyOutboxMutations([first, second, terminal, dependent], 100))
      .toEqual([dependent]);
  });

  it("blocks missing, cyclic, cross-entity, and non-terminal alias corruption", () => {
    const missing = superseded("missing-1", "gone", "rdo-a");
    const cycleA = superseded("cycle-a", "cycle-b", "rdo-cycle");
    const cycleB = superseded("cycle-b", "cycle-a", "rdo-cycle");
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
  it("reports missing dependencies and cycles without sending them", () => {
    const first = mutation("first", "PENDING", ["second"]);
    const second = mutation("second", "PENDING", ["first"]);
    const missing = mutation("missing", "PENDING", ["gone"]);

    const analysis = analyzeOutboxDependencies([
      first,
      second,
      missing,
    ]);

    expect(analysis.cycles).toEqual(
      expect.arrayContaining(["first", "second"]),
    );
    expect(analysis.missingDependencies).toEqual([
      { mutationId: "missing", dependencyId: "gone" },
    ]);
    expect(
      selectReadyOutboxMutations([first, second, missing], 100),
    ).toEqual([]);
  });
});
