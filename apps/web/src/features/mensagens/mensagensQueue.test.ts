import { describe, expect, it } from "vitest";

import {
  buildQueuedMessage,
  mensagemStatusLabel,
} from "./mensagensQueue";

describe("mensagens offline queue", () => {
  it("keeps the message local and makes finalization depend on every upload", () => {
    const queued = buildQueuedMessage({
      conversaId: "00000000-0000-4000-8000-000000000010",
      autorId: "00000000-0000-4000-8000-000000000020",
      autorNome: "Operador Beta",
      corpo: "Foto da frente de serviço",
      files: [
        new File(["first"], "frente.jpg", { type: "image/jpeg" }),
        new File(["second"], "medicao.pdf", { type: "application/pdf" }),
      ],
      now: "2026-07-14T12:00:00.000Z",
      ids: [
        "00000000-0000-4000-8000-000000000101",
        "00000000-0000-4000-8000-000000000102",
        "00000000-0000-4000-8000-000000000103",
        "00000000-0000-4000-8000-000000000104",
        "00000000-0000-4000-8000-000000000105",
      ],
    });

    expect(queued.message.syncStatus).toBe("NA_FILA");
    expect(queued.attachments).toHaveLength(2);
    expect(queued.uploadMutations).toHaveLength(2);
    expect(queued.messageMutation.transport).toBe("SYNC_PUSH");
    expect(queued.messageMutation.dependsOnMutationIds).toEqual(
      queued.uploadMutations.map((mutation) => mutation.clientMutationId),
    );
    expect(queued.messageMutation.payload.anexos).toEqual([
      { uploadMutationId: queued.uploadMutations[0].clientMutationId },
      { uploadMutationId: queued.uploadMutations[1].clientMutationId },
    ]);
  });

  it("uses clear Portuguese states for every local lifecycle value", () => {
    expect(mensagemStatusLabel("LOCAL")).toBe("Local");
    expect(mensagemStatusLabel("NA_FILA")).toBe("Na fila");
    expect(mensagemStatusLabel("SINCRONIZANDO")).toBe("Sincronizando");
    expect(mensagemStatusLabel("SINCRONIZADO")).toBe("Sincronizado");
    expect(mensagemStatusLabel("FALHOU")).toBe("Falhou");
  });
});
