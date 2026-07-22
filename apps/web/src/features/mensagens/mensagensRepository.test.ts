import "fake-indexeddb/auto";

import { openDB } from "idb";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { setSession } from "../auth/authSession";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import {
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../../lib/db/cortexDb";
import {
  materializeCanonicalMessageMutation,
  queueMessage,
} from "./mensagensRepository";

describe("mensagens IndexedDB repository", () => {
  let ownerId: string;
  const obraId = "00000000-0000-4000-8000-000000000001";
  const conversaId = "00000000-0000-4000-8000-000000000010";

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

  async function storeConversation(): Promise<void> {
    const database = await getCortexDb();
    await database.put("mensagem_conversas", {
      id: conversaId,
      tipo: "OBRA",
      titulo: "Frente de serviço",
      obraId,
      equipeId: null,
      status: "ATIVA",
      participantes: [],
      criadaEm: "2026-07-20T10:00:00.000Z",
      atualizadaEm: "2026-07-20T10:00:00.000Z",
      versaoEntidade: 1,
    });
  }

  it("persists the attachment Blob while leaving its domain mutation pending until the objects are available", async () => {
    await storeConversation();
    const queued = await queueMessage({
      conversaId,
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
    const storedMutations = await reopened.getAll("outbox_mutations");

    expect(storedAttachment.arquivo).toBeInstanceOf(Blob);
    expect(await storedAttachment.arquivo.text()).toBe("conteudo persistido");
    expect(storedAttachment.sha256).toMatch(/^[a-f0-9]{64}$/);
    expect(messageMutation).toBeUndefined();
    expect(storedMutations).toEqual([
      expect.objectContaining({
        clientMutationId: storedAttachment.uploadMutationId,
        transport: "OBJECT_UPLOAD",
        status: "PENDING",
      }),
    ]);
    reopened.close();
  });

  it("writes a text-only message with its canonical envelope and ontology event in one local commit", async () => {
    await storeConversation();

    const queued = await queueMessage({
      conversaId,
      corpo: "Mensagem institucional offline",
      files: [],
    });
    const database = await getCortexDb();
    const mutation = await database.get(
      "outbox_mutations",
      queued.clientMutationId,
    );

    expect(mutation).toMatchObject({
      contractVersion: 13,
      clientMutationId: queued.clientMutationId,
      entidadeTipo: "MENSAGEM",
      entidadeId: queued.id,
      operacao: "CRIAR_MENSAGEM",
      payload: {
        conversaId,
        corpo: "Mensagem institucional offline",
        anexos: [],
      },
      trace: {
        actorId: ownerId,
        authorizationScope: [obraId],
      },
    });
    expect(mutation?.payload).not.toHaveProperty("criadaNoClienteEm");
    expect(
      await database.get(
        "operational_events",
        mutation?.trace.ontologyEventId ?? "missing-event",
      ),
    ).toMatchObject({
      contractVersion: 13,
      clientMutationId: queued.clientMutationId,
      type: "MENSAGEM_CRIADA",
      result: "PENDING",
      principalEntity: { tipo: "MENSAGEM", id: queued.id },
    });
  });

  it("materializes an attachment message once with immutable server object references", async () => {
    await storeConversation();
    const queued = await queueMessage({
      conversaId,
      corpo: "Registro com evidência",
      files: [
        new File(["evidência"], "evidencia.txt", {
          type: "text/plain",
        }),
      ],
    });
    const database = await getCortexDb();
    const attachment = queued.anexos[0];
    const upload = await database.get(
      "outbox_mutations",
      attachment.uploadMutationId ?? "missing-upload",
    );
    const objectId = "00000000-0000-4000-8000-000000000099";

    await database.put("mensagem_anexos", {
      ...attachment,
      objetoId: objectId,
      syncStatus: "SINCRONIZADO",
      updatedAt: "2026-07-20T11:00:00.000Z",
    });
    await database.put("outbox_mutations", {
      ...upload!,
      status: "SYNCED",
      updatedAt: "2026-07-20T11:00:00.000Z",
    });

    const mutation = await materializeCanonicalMessageMutation(queued.id);
    expect(mutation).toMatchObject({
      contractVersion: 13,
      entidadeTipo: "MENSAGEM",
      entidadeId: queued.id,
      operacao: "CRIAR_MENSAGEM",
      payload: {
        conversaId,
        corpo: "Registro com evidência",
        anexos: [
          {
            objetoId: objectId,
            sha256: attachment.sha256,
          },
        ],
      },
    });
    const payloadBeforeRetry = mutation?.payload;
    const hashBeforeRetry = mutation?.trace.payloadHash;

    await expect(
      materializeCanonicalMessageMutation(queued.id),
    ).resolves.toBeNull();
    expect(
      await database.get("outbox_mutations", mutation?.clientMutationId ?? ""),
    ).toMatchObject({
      payload: payloadBeforeRetry,
      trace: { payloadHash: hashBeforeRetry },
    });
  });

  it("keeps an empty worksite scope explicit for a global direct conversation", async () => {
    setSession({
      colaboradorId: ownerId,
      nome: "Administrador operacional",
      papelAcesso: "ALFA",
      escopoGlobal: true,
      obraIds: [],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    const directConversationId =
      "00000000-0000-4000-8000-000000000021";
    const database = await getCortexDb();
    await database.put("mensagem_conversas", {
      id: directConversationId,
      tipo: "DIRETA",
      titulo: null,
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      participantes: [],
      criadaEm: "2026-07-20T10:00:00.000Z",
      atualizadaEm: "2026-07-20T10:00:00.000Z",
      versaoEntidade: 1,
    });

    const queued = await queueMessage({
      conversaId: directConversationId,
      corpo: "Mensagem em conversa direta",
      files: [],
    });

    await expect(
      database.get("outbox_mutations", queued.clientMutationId),
    ).resolves.toMatchObject({
      contractVersion: 13,
      entidadeTipo: "MENSAGEM",
      trace: { authorizationScope: [] },
    });
  });
});
