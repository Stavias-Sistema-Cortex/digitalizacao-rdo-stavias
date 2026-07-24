import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
  reconcileCanonicalConflict,
  rejectMutationLocally,
  returnMutationToPending,
} from "../sync/syncStorage";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import { databaseNameForScope } from "./localDataNamespace";
import {
  createTarefa,
  deleteTarefa,
  setTarefaConclusao,
  updateTarefa,
} from "./tarefaRepository";

const ACTOR_ID = "00000000-0000-4000-8000-000000000161";
const OBRA_ID = "00000000-0000-4000-8000-000000000162";
const DEVICE_ID = "00000000-0000-4000-8000-000000000163";
const actor = {
  colaboradorId: ACTOR_ID,
  nome: "Encarregada de campo",
};

let databaseName = "";

function input() {
  return {
    obraId: OBRA_ID,
    equipe: "Fresagem",
    titulo: "Sinalizar desvio",
    observacoes: "Antes do início do turno.",
    criadaPor: actor.nome,
    criadaPorColaboradorId: ACTOR_ID,
    responsavelEquipe: "Equipe Fresagem",
    responsavelColaboradorId: null,
    prioridade: 1 as const,
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  vi.stubGlobal("BroadcastChannel", undefined);
  setSession({
    colaboradorId: ACTOR_ID,
    nome: actor.nome,
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(
    ACTOR_ID,
    `BETA:${OBRA_ID}`,
  );
  const database = await getCortexDb();
  await database.put("sync_state", {
    key: "default",
    deviceId: DEVICE_ID,
    usuarioId: ACTOR_ID,
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
    isSyncing: false,
    lastSyncStartedAt: null,
    lastSyncCompletedAt: null,
    lastSyncError: null,
  });
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) {
    await deleteDB(databaseName);
  }
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("tarefaRepository canonical sync", () => {
  it("commits task, v13 envelope and ontology event atomically", async () => {
    const tarefa = await createTarefa(input(), actor);
    const database = await getCortexDb();
    const [mutation] = (await database.getAll("outbox_mutations"))
      .filter((candidate) => candidate.entidadeId === tarefa.id);
    const [event] = (await database.getAll("operational_events"))
      .filter((candidate) => candidate.principalEntity.id === tarefa.id);

    expect(tarefa).toMatchObject({
      obraId: OBRA_ID,
      syncStatus: "PENDING_SYNC",
      versaoEntidade: null,
      deletadaEm: null,
    });
    expect(await database.get("tarefas", tarefa.id)).toEqual(tarefa);
    expect(mutation).toMatchObject({
      schemaVersion: 13,
      deviceId: DEVICE_ID,
      userId: ACTOR_ID,
      obraId: OBRA_ID,
      entityType: "TAREFA",
      entidadeTipo: "TAREFA",
      entidadeId: tarefa.id,
      operation: "CREATE",
      operacao: "CRIAR_TAREFA",
      baseVersao: null,
      trace: {
        actorId: ACTOR_ID,
        authorizationScope: [OBRA_ID],
      },
    });
    expect(event).toMatchObject({
      schemaVersion: 13,
      type: "TAREFA_CRIADA",
      clientMutationId: mutation?.clientMutationId,
      principalEntity: {
        tipo: "TAREFA",
        id: tarefa.id,
      },
      obraId: OBRA_ID,
    });
  });

  it("chains completion and deletion without losing the offline trail", async () => {
    const criada = await createTarefa(input(), actor);
    await setTarefaConclusao(criada.id, true, actor);
    await deleteTarefa(criada.id, actor);

    const database = await getCortexDb();
    const mutations = (await database.getAll("outbox_mutations"))
      .filter((mutation) => mutation.entidadeId === criada.id);
    const events = (await database.getAll("operational_events"))
      .filter((event) => event.principalEntity.id === criada.id);
    const stored = await database.get("tarefas", criada.id);
    const creation = mutations.find(
      (mutation) => mutation.operacao === "CRIAR_TAREFA",
    );
    const completion = mutations.find(
      (mutation) => mutation.operacao === "CONCLUIR_TAREFA",
    );
    const deletion = mutations.find(
      (mutation) => mutation.operacao === "EXCLUIR_TAREFA",
    );

    expect(mutations).toHaveLength(3);
    expect(completion?.dependsOnMutationIds).toEqual([
      creation?.clientMutationId,
    ]);
    expect(deletion?.dependsOnMutationIds).toEqual([
      completion?.clientMutationId,
    ]);
    expect(completion).toMatchObject({
      operation: "TRANSITION",
      baseVersao: 1,
    });
    expect(deletion).toMatchObject({
      operation: "DELETE",
      baseVersao: 2,
    });
    expect(events.map((event) => event.type)).toEqual(
      expect.arrayContaining([
        "TAREFA_CRIADA",
        "TAREFA_CONCLUIDA",
        "TAREFA_EXCLUIDA",
      ]),
    );
    expect(stored).toMatchObject({
      syncStatus: "PENDING_SYNC",
      deletadaEm: expect.any(String),
    });
  });

  it("queues a canonical field update without changing transition state", async () => {
    const criada = await createTarefa(input(), actor);
    const updated = await updateTarefa(
      criada.id,
      {
        titulo: "Sinalizar desvio atualizado",
        prioridade: 3,
        responsavelEquipe: "Equipe de Segurança",
        responsavelColaboradorId: null,
      },
      actor,
    );
    const database = await getCortexDb();
    const mutations = (await database.getAll("outbox_mutations"))
      .filter((mutation) => mutation.entidadeId === criada.id);
    const creation = mutations.find(
      (mutation) => mutation.operacao === "CRIAR_TAREFA",
    );
    const update = mutations.find(
      (mutation) => mutation.operacao === "ATUALIZAR_TAREFA",
    );

    expect(updated).toMatchObject({
      titulo: "Sinalizar desvio atualizado",
      prioridade: 3,
      responsavelEquipe: "Equipe de Segurança",
      concluida: false,
      concluidaEm: null,
      syncStatus: "PENDING_SYNC",
    });
    expect(update).toMatchObject({
      entityType: "TAREFA",
      operation: "UPDATE",
      baseVersao: 1,
      dependsOnMutationIds: [creation?.clientMutationId],
    });
    expect(
      (await database.getAll("operational_events")).find(
        (event) => event.clientMutationId === update?.clientMutationId,
      ),
    ).toMatchObject({ type: "TAREFA_ATUALIZADA" });
  });

  it("keeps the causal tail when several offline edits share one timestamp", async () => {
    const OriginalDate = Date;
    const fixedTime = OriginalDate.parse("2026-07-23T12:00:00.000Z");
    vi.stubGlobal(
      "Date",
      class extends OriginalDate {
        constructor(value?: string | number | Date) {
          super(value === undefined ? fixedTime : value);
        }

        static override now() {
          return fixedTime;
        }
      },
    );
    const randomUuid = vi.spyOn(crypto, "randomUUID");
    [
      "00000000-0000-4000-8000-000000000100",
      "00000000-0000-4000-8000-000000000900",
      "00000000-0000-4000-8000-000000000901",
      "00000000-0000-4000-8000-000000000101",
      "00000000-0000-4000-8000-000000000102",
      "00000000-0000-4000-8000-000000000500",
      "00000000-0000-4000-8000-000000000501",
    ].forEach((id) => randomUuid.mockReturnValueOnce(id));

    try {
      const criada = await createTarefa(input(), actor);
      await updateTarefa(
        criada.id,
        { titulo: "Editar antes de concluir" },
        actor,
      );
      await setTarefaConclusao(criada.id, true, actor);

      const database = await getCortexDb();
      const mutations = (await database.getAll("outbox_mutations"))
        .filter((mutation) => mutation.entidadeId === criada.id);
      const update = mutations.find(
        (mutation) => mutation.operacao === "ATUALIZAR_TAREFA",
      );
      const completion = mutations.find(
        (mutation) => mutation.operacao === "CONCLUIR_TAREFA",
      );

      expect(completion?.dependsOnMutationIds).toEqual([
        update?.clientMutationId,
      ]);
      expect(completion?.causationId).toBe(update?.clientMutationId);
    } finally {
      vi.stubGlobal("Date", OriginalDate);
    }
  });

  it("rejects a stale concurrent edit instead of forking the task chain", async () => {
    const criada = await createTarefa(input(), actor);

    const outcomes = await Promise.allSettled([
      updateTarefa(
        criada.id,
        { titulo: "Primeira edição concorrente" },
        actor,
      ),
      updateTarefa(
        criada.id,
        { prioridade: 3 },
        actor,
      ),
    ]);
    const database = await getCortexDb();
    const mutations = (await database.getAll("outbox_mutations"))
      .filter((mutation) => mutation.entidadeId === criada.id);

    expect(
      outcomes.map((outcome) => outcome.status).sort(),
    ).toEqual(["fulfilled", "rejected"]);
    expect(mutations).toHaveLength(2);
    expect(
      mutations.filter(
        (mutation) => mutation.operacao === "ATUALIZAR_TAREFA",
      ),
    ).toHaveLength(1);
  });

  it("applies an earlier server result without overwriting the newer pending edit", async () => {
    const criada = await createTarefa(input(), actor);
    const concluida = await setTarefaConclusao(
      criada.id,
      true,
      actor,
    );
    const database = await getCortexDb();
    const mutations = (await database.getAll("outbox_mutations"))
      .filter((mutation) => mutation.entidadeId === criada.id);
    const creation = mutations.find(
      (mutation) => mutation.operacao === "CRIAR_TAREFA",
    );
    const completion = mutations.find(
      (mutation) => mutation.operacao === "CONCLUIR_TAREFA",
    );

    expect(creation).toBeDefined();
    await markMutationAsSyncing(creation!);
    await applyPushResultAtomically({
      clientMutationId: creation!.clientMutationId,
      status: "APLICADA",
      entidadeTipo: "TAREFA",
      entidadeId: criada.id,
      resultado: { versaoEntidade: 1 },
    });

    expect(await database.get("tarefas", criada.id)).toMatchObject({
      concluida: concluida?.concluida,
      concluidaEm: concluida?.concluidaEm,
      versaoEntidade: 1,
      syncStatus: "PENDING_SYNC",
    });
    expect(
      await database.get(
        "outbox_mutations",
        creation!.clientMutationId,
      ),
    ).toMatchObject({ status: "SYNCED" });
    expect(
      await database.get(
        "outbox_mutations",
        completion!.clientMutationId,
      ),
    ).toMatchObject({ status: "PENDING" });
  });

  it("keeps a newer pending tombstone when an earlier task edit conflicts", async () => {
    const criada = await createTarefa(input(), actor);
    const database = await getCortexDb();
    const creation = (await database.getAll("outbox_mutations"))
      .find((mutation) => mutation.entidadeId === criada.id);
    await markMutationAsSyncing(creation!);
    await applyPushResultAtomically({
      clientMutationId: creation!.clientMutationId,
      status: "APLICADA",
      entidadeTipo: "TAREFA",
      entidadeId: criada.id,
      resultado: { versaoEntidade: 1 },
    });

    await updateTarefa(
      criada.id,
      { titulo: "Edição local mais recente" },
      actor,
    );
    await deleteTarefa(criada.id, actor);
    const mutations = (await database.getAll("outbox_mutations"))
      .filter((mutation) => mutation.entidadeId === criada.id);
    const update = mutations.find(
      (mutation) => mutation.operacao === "ATUALIZAR_TAREFA",
    );
    await markMutationAsSyncing(update!);
    await applyPushResultAtomically({
      clientMutationId: update!.clientMutationId,
      status: "CONFLITO",
      entidadeTipo: "TAREFA",
      entidadeId: criada.id,
      resultado: {},
      conflito: {
        versaoAtual: 2,
        remoteCompleto: true,
        snapshotRemoto: {
          ...creation!.payload,
          observacoes: "Alteração concorrente no servidor.",
        },
      },
      erro: "Conflito de versão.",
    });

    await expect(
      reconcileCanonicalConflict(update!.clientMutationId),
    ).resolves.toBeNull();
    expect(await database.get("tarefas", criada.id)).toMatchObject({
      titulo: "Edição local mais recente",
      deletadaEm: expect.any(String),
      versaoEntidade: 2,
      syncStatus: "CONFLICT",
    });
  });

  it("returns an interrupted task mutation to retry without changing its domain content", async () => {
    const criada = await createTarefa(input(), actor);
    const database = await getCortexDb();
    const [creation] = (await database.getAll("outbox_mutations"))
      .filter((mutation) => mutation.entidadeId === criada.id);

    await markMutationAsSyncing(creation);
    await returnMutationToPending(
      creation.clientMutationId,
      "Conexão interrompida.",
    );

    expect(await database.get("tarefas", criada.id)).toMatchObject({
      titulo: criada.titulo,
      syncStatus: "PENDING_SYNC",
      versaoEntidade: null,
    });
    expect(
      await database.get(
        "outbox_mutations",
        creation.clientMutationId,
      ),
    ).toMatchObject({
      status: "PENDING",
      ultimoErro: "Conexão interrompida.",
    });
  });

  it("surfaces a locally rejected task envelope without deleting task content", async () => {
    const criada = await createTarefa(input(), actor);
    const database = await getCortexDb();
    const [creation] = (await database.getAll("outbox_mutations"))
      .filter((mutation) => mutation.entidadeId === criada.id);

    await rejectMutationLocally(
      creation.clientMutationId,
      "LOCAL_CANONICAL_INVALID",
      "Envelope local inválido.",
    );

    expect(await database.get("tarefas", criada.id)).toMatchObject({
      titulo: criada.titulo,
      syncStatus: "ERROR",
      versaoEntidade: null,
    });
    expect(
      await database.get(
        "outbox_mutations",
        creation.clientMutationId,
      ),
    ).toMatchObject({
      status: "REJECTED",
      ultimoErro: "Envelope local inválido.",
    });
  });

  it("rejects task authorship or worksite outside the active session", async () => {
    const database = await getCortexDb();

    await expect(
      createTarefa(input(), {
        colaboradorId:
          "00000000-0000-4000-8000-000000000199",
        nome: "Outro operador",
      }),
    ).rejects.toThrow(/autoria.*sessão/i);
    await expect(
      createTarefa(
        {
          ...input(),
          obraId: "00000000-0000-4000-8000-000000000198",
        },
        actor,
      ),
    ).rejects.toThrow(/obra.*escopo/i);

    expect(await database.getAll("tarefas")).toHaveLength(0);
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
    expect(await database.getAll("operational_events")).toHaveLength(0);
  });
});
