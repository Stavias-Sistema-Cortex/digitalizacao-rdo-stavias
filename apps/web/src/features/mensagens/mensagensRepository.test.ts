import "fake-indexeddb/auto";

import { openDB } from "idb";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { setSession } from "../auth/authSession";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { CORTEX_DATABASE_VERSION } from "../../lib/db/cortexDb";
import { queueMessage } from "./mensagensRepository";

describe("mensagens IndexedDB repository", () => {
  let ownerId: string;

  beforeEach(() => {
    vi.stubGlobal("BroadcastChannel", undefined);
    ownerId = crypto.randomUUID();
    setSession({
      colaboradorId: ownerId,
      nome: "Operador de campo",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: ["00000000-0000-4000-8000-000000000001"],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
  });

  it("persists the attachment Blob and dependency graph for a later reload", async () => {
    const queued = await queueMessage({
      conversaId: "00000000-0000-4000-8000-000000000010",
      corpo: "Anexo salvo offline",
      files: [
        new File(["conteudo persistido"], "registro.txt", {
          type: "text/plain",
        }),
      ],
    });

    const databaseName = await databaseNameForScope(
      ownerId,
      "BETA:00000000-0000-4000-8000-000000000001",
    );
    const reopened = await openDB(databaseName, CORTEX_DATABASE_VERSION);
    const storedAttachment = await reopened.get(
      "mensagem_anexos",
      queued.anexos[0].id,
    );
    const messageMutation = await reopened.get(
      "outbox_mutations",
      queued.clientMutationId,
    );

    expect(storedAttachment.arquivo).toBeInstanceOf(Blob);
    expect(await storedAttachment.arquivo.text()).toBe("conteudo persistido");
    expect(storedAttachment.sha256).toMatch(/^[a-f0-9]{64}$/);
    expect(messageMutation.dependsOnMutationIds).toEqual([
      storedAttachment.uploadMutationId,
    ]);
    reopened.close();
  });
});
