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
): OutboxMutationRecord {
  return {
    clientMutationId: id,
    entidadeTipo: "MENSAGEM",
    entidadeId: id,
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

describe("selectReadyOutboxMutations", () => {
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
