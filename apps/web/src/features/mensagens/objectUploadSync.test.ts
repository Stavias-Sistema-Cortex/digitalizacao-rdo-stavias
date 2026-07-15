import { describe, expect, it } from "vitest";

import {
  resolveUploadReference,
  verifyUploadIntegrity,
} from "./objectUploadSync";

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
});
