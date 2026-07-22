import { describe, expect, it } from "vitest";

import {
  CANONICAL_UPLOAD_REFERENCE_BLOCKED,
  dependentAfterUploadResolution,
  resolveUploadReference,
  verifyUploadIntegrity,
} from "./objectUploadSync";
import type { OutboxMutationRecord } from "../../lib/db/db.types";

function dependentMutation(): OutboxMutationRecord {
  return {
    clientMutationId: "message-1",
    entidadeTipo: "MENSAGEM",
    entidadeId: "message-1",
    operacao: "CRIAR_MENSAGEM",
    baseVersao: null,
    payload: { anexos: [{ uploadMutationId: "upload-a" }] },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-07-21T12:00:00.000Z",
    updatedAt: "2026-07-21T12:00:00.000Z",
    dependsOnMutationIds: ["upload-a"],
  };
}

describe("message attachment upload sync", () => {
  it("replaces only the uploaded dependency with the server object reference", () => {
    const payload = {
      conversaId: "conversation",
      anexos: [
        { uploadMutationId: "upload-a" },
        { uploadMutationId: "upload-b" },
      ],
    };

    expect(
      resolveUploadReference(payload, "upload-a", "object-a", "a".repeat(64)),
    ).toEqual({
      conversaId: "conversation",
      anexos: [
        { objetoId: "object-a", sha256: "a".repeat(64) },
        { uploadMutationId: "upload-b" },
      ],
    });
  });

  it("rejects a server response whose hash or size differs from the local blob", () => {
    expect(() =>
      verifyUploadIntegrity(
        { id: "object", sha256: "b".repeat(64), tamanhoBytes: 4 },
        "a".repeat(64),
        4,
      ),
    ).toThrow("integridade");
    expect(() =>
      verifyUploadIntegrity(
        { id: "object", sha256: "a".repeat(64), tamanhoBytes: 5 },
        "a".repeat(64),
        4,
      ),
    ).toThrow("integridade");
  });

  it("blocks a canonical dependent instead of sending an unresolved placeholder", () => {
    const canonical = {
      ...dependentMutation(),
      schemaVersion: 13,
    } as OutboxMutationRecord;
    const resolved = dependentAfterUploadResolution(
      canonical,
      "upload-a",
      "object-a",
      "a".repeat(64),
      "2026-07-21T13:00:00.000Z",
    );

    expect(resolved.payload).toEqual(canonical.payload);
    expect(resolved.blockedReason).toBe(CANONICAL_UPLOAD_REFERENCE_BLOCKED);
    expect(resolved.status).toBe("PENDING");
  });
});
