import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type { MensagemLocalRecord } from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { commitLocalMutation } from "./localMutationCoordinator";
import { canonicalMutationJson } from "./mutationEnvelope";
import { resolveCanonicalUploadReplacements } from "./syncStorage";
import { pushOutbox } from "./pushOutbox";

const mocks = vi.hoisted(() => ({ push: vi.fn() }));
vi.mock("./syncApiClient", () => ({ pushMutationsApi: mocks.push }));

const OBRA_ID = "00000000-0000-4000-8000-000000000301";
const DEVICE_ID = "00000000-0000-4000-8000-000000000302";
const MESSAGE_ID = "00000000-0000-4000-8000-000000000303";
const CONVERSATION_ID = "00000000-0000-4000-8000-000000000304";
const UPLOAD_ID = "00000000-0000-4000-8000-000000000305";
const ATTACHMENT_ID = "00000000-0000-4000-8000-000000000306";
const ORIGINAL_ID = "00000000-0000-4000-8000-000000000307";
const ORIGINAL_EVENT_ID = "00000000-0000-4000-8000-000000000308";
const REPLACEMENT_ID = "00000000-0000-4000-8000-000000000309";
const REPLACEMENT_EVENT_ID = "00000000-0000-4000-8000-000000000310";
const INDEPENDENT_MESSAGE_ID = "00000000-0000-4000-8000-000000000312";
const INDEPENDENT_ID = "00000000-0000-4000-8000-000000000313";
const INDEPENDENT_EVENT_ID = "00000000-0000-4000-8000-000000000314";

let userId = "";
let databaseName = "";

beforeEach(async () => {
  vi.clearAllMocks();
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Operador",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
});

describe("canonical upload dependency resolution", () => {
  it("supersedes the immutable placeholder with a causally linked envelope", async () => {
    const message = {
      id: MESSAGE_ID,
      conversaId: CONVERSATION_ID,
      autorId: userId,
      autorNome: "Operador",
      corpo: "Foto da frente",
      status: "ATIVA",
      clientMutationId: ORIGINAL_ID,
      criadaNoClienteEm: "2026-07-22T12:00:00.000Z",
      criadaEm: null,
      editadaEm: null,
      deletadaEm: null,
      versaoEntidade: null,
      syncStatus: "NA_FILA",
      ultimoErro: null,
      updatedAt: "2026-07-22T12:00:00.000Z",
      anexos: [{ uploadMutationId: UPLOAD_ID }],
    } as MensagemLocalRecord & {
      anexos: Array<{ uploadMutationId: string }>;
    };
    const committed = await commitLocalMutation({
      clientMutationId: ORIGINAL_ID,
      ontologyEventId: ORIGINAL_EVENT_ID,
      deviceId: DEVICE_ID,
      userId,
      obraId: OBRA_ID,
      entityType: "MENSAGEM",
      entityId: MESSAGE_ID,
      operation: "CREATE",
      transportOperation: "CRIAR_MENSAGEM",
      baseVersion: null,
      occurredAt: "2026-07-22T12:00:00.000Z",
      previousSnapshot: {},
      nextSnapshot: { ...message },
      eventType: "ENTIDADE_RELACIONADA",
      dependsOnMutationIds: [UPLOAD_ID],
      write: () => [{ store: "mensagens", value: message, principal: true }],
    });
    const immutableBefore = canonicalMutationJson({
      payload: committed.mutation.payload,
      trace: committed.mutation.trace,
      fieldPatch: committed.mutation.fieldPatch,
    });
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      ...committed.mutation,
      blockedReason: "CANONICAL_UPLOAD_REFERENCE_REQUIRES_REPLACEMENT",
    });
    await database.put("mensagem_anexos", {
      id: ATTACHMENT_ID,
      mensagemId: MESSAGE_ID,
      conversaId: CONVERSATION_ID,
      objetoId: "00000000-0000-4000-8000-000000000311",
      uploadMutationId: UPLOAD_ID,
      nome: "foto.jpg",
      mediaType: "image/jpeg",
      tamanhoBytes: 10,
      sha256: "a".repeat(64),
      ordem: 0,
      arquivo: null,
      syncStatus: "SINCRONIZADO",
      ultimoErro: null,
      createdAt: "2026-07-22T12:00:00.000Z",
      updatedAt: "2026-07-22T12:00:01.000Z",
    });

    const created = await resolveCanonicalUploadReplacements(undefined, {
      replacementMutationId: () => REPLACEMENT_ID,
      replacementEventId: () => REPLACEMENT_EVENT_ID,
      occurredAt: () => "2026-07-22T12:00:02.000Z",
    });

    const original = await database.get("outbox_mutations", ORIGINAL_ID);
    const replacement = await database.get(
      "outbox_mutations",
      REPLACEMENT_ID,
    );
    expect(created).toBe(1);
    expect(original).toMatchObject({
      status: "REJECTED",
      lastSafeCode: "SUPERSEDED_BY_REPLACEMENT",
    });
    expect(
      canonicalMutationJson({
        payload: original?.payload,
        trace: original?.schemaVersion === 13 ? original.trace : null,
        fieldPatch:
          original?.schemaVersion === 13 ? original.fieldPatch : null,
      }),
    ).toBe(immutableBefore);
    expect(replacement).toMatchObject({
      clientMutationId: REPLACEMENT_ID,
      causationId: ORIGINAL_ID,
      correlationId: ORIGINAL_ID,
      dependsOnMutationIds: [],
      payload: expect.objectContaining({
        anexos: [
          {
            objetoId: "00000000-0000-4000-8000-000000000311",
            sha256: "a".repeat(64),
          },
        ],
      }),
    });
    expect(
      await database.get("operational_events", REPLACEMENT_EVENT_ID),
    ).toMatchObject({
      clientMutationId: REPLACEMENT_ID,
      causationId: ORIGINAL_ID,
      result: "PENDING",
    });

    expect(
      await resolveCanonicalUploadReplacements(undefined, {
        replacementMutationId: () => REPLACEMENT_ID,
        replacementEventId: () => REPLACEMENT_EVENT_ID,
        occurredAt: () => "2026-07-22T12:00:02.000Z",
      }),
    ).toBe(0);
  });

  it("quarantines a tampered blocked envelope and still pushes an independent row", async () => {
    const blockedMessage = {
      id: MESSAGE_ID,
      conversaId: CONVERSATION_ID,
      autorId: userId,
      autorNome: "Operador",
      corpo: "Foto da frente",
      status: "ATIVA",
      clientMutationId: ORIGINAL_ID,
      criadaNoClienteEm: "2026-07-22T12:00:00.000Z",
      criadaEm: null,
      editadaEm: null,
      deletadaEm: null,
      versaoEntidade: null,
      syncStatus: "NA_FILA",
      ultimoErro: null,
      updatedAt: "2026-07-22T12:00:00.000Z",
      anexos: [{ uploadMutationId: UPLOAD_ID }],
    } as MensagemLocalRecord & {
      anexos: Array<{ uploadMutationId: string }>;
    };
    const blocked = await commitLocalMutation({
      clientMutationId: ORIGINAL_ID,
      ontologyEventId: ORIGINAL_EVENT_ID,
      deviceId: DEVICE_ID,
      userId,
      obraId: OBRA_ID,
      entityType: "MENSAGEM",
      entityId: MESSAGE_ID,
      operation: "CREATE",
      transportOperation: "CRIAR_MENSAGEM",
      baseVersion: null,
      occurredAt: "2026-07-22T12:00:00.000Z",
      previousSnapshot: {},
      nextSnapshot: blockedMessage,
      eventType: "ENTIDADE_RELACIONADA",
      dependsOnMutationIds: [UPLOAD_ID],
      write: () => [
        { store: "mensagens", value: blockedMessage, principal: true },
      ],
    });
    const independentMessage = {
      ...blockedMessage,
      id: INDEPENDENT_MESSAGE_ID,
      clientMutationId: INDEPENDENT_ID,
      corpo: "Apontamento independente",
      anexos: [],
      criadaNoClienteEm: "2026-07-22T12:00:01.000Z",
      updatedAt: "2026-07-22T12:00:01.000Z",
    };
    await commitLocalMutation({
      clientMutationId: INDEPENDENT_ID,
      ontologyEventId: INDEPENDENT_EVENT_ID,
      deviceId: DEVICE_ID,
      userId,
      obraId: OBRA_ID,
      entityType: "MENSAGEM",
      entityId: INDEPENDENT_MESSAGE_ID,
      operation: "CREATE",
      transportOperation: "CRIAR_MENSAGEM",
      baseVersion: null,
      occurredAt: "2026-07-22T12:00:01.000Z",
      previousSnapshot: {},
      nextSnapshot: independentMessage,
      eventType: "ENTIDADE_RELACIONADA",
      write: () => [
        { store: "mensagens", value: independentMessage, principal: true },
      ],
    });
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      ...blocked.mutation,
      payload: { ...blocked.mutation.payload, corpo: "adulterado" },
      blockedReason: "CANONICAL_UPLOAD_REFERENCE_REQUIRES_REPLACEMENT",
    });
    await database.put("mensagem_anexos", {
      id: ATTACHMENT_ID,
      mensagemId: MESSAGE_ID,
      conversaId: CONVERSATION_ID,
      objetoId: "00000000-0000-4000-8000-000000000311",
      uploadMutationId: UPLOAD_ID,
      nome: "foto.jpg",
      mediaType: "image/jpeg",
      tamanhoBytes: 10,
      sha256: "a".repeat(64),
      ordem: 0,
      arquivo: null,
      syncStatus: "SINCRONIZADO",
      ultimoErro: null,
      createdAt: "2026-07-22T12:00:00.000Z",
      updatedAt: "2026-07-22T12:00:01.000Z",
    });
    mocks.push.mockResolvedValue({
      resultados: [
        { clientMutationId: INDEPENDENT_ID, status: "APLICADA" },
      ],
    });

    expect(
      await resolveCanonicalUploadReplacements(undefined, {
        replacementMutationId: () => REPLACEMENT_ID,
        replacementEventId: () => REPLACEMENT_EVENT_ID,
        occurredAt: () => "2026-07-22T12:00:02.000Z",
      }),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID)).toMatchObject({
      status: "REJECTED",
      lastSafeCode: "LOCAL_CANONICAL_UPLOAD_INVALID",
    });

    const summary = await pushOutbox(DEVICE_ID);
    expect(summary).toMatchObject({ pushed: 1, applied: 1 });
    expect(mocks.push).toHaveBeenCalledWith({
      dispositivoId: DEVICE_ID,
      mutacoes: [
        expect.objectContaining({ clientMutationId: INDEPENDENT_ID }),
      ],
    });
    expect(await database.get("outbox_mutations", REPLACEMENT_ID)).toBeUndefined();
  });
});
