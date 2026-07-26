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
  closeCortexDb,
  getCortexDb,
} from "../db/cortexDb";
import type {
  LocalSyncStatus,
  OutboxMutationRecord,
  TarefaRecord,
} from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import {
  applyPulledEventsAtomically,
  recoverInterruptedMutations,
} from "./syncStorage";

const USER_ID = "00000000-0000-4000-8000-000000000161";
const OBRA_ID = "00000000-0000-4000-8000-000000000162";
const TASK_ID = "00000000-0000-4000-8000-000000000199";
const MUTATION_ID = "00000000-0000-4000-8000-000000000196";

type ConvergentTarefaRecord = TarefaRecord & {
  versaoEntidade?: number | null;
  syncStatus?: LocalSyncStatus;
  deletadaEm?: string | null;
};

let databaseName = "";

function task(
  overrides: Partial<ConvergentTarefaRecord> = {},
): ConvergentTarefaRecord {
  return {
    id: TASK_ID,
    obraId: OBRA_ID,
    equipe: "Fresagem",
    titulo: "Registro local pendente",
    observacoes: "",
    criadaPor: "Encarregada de campo",
    criadaPorColaboradorId: USER_ID,
    responsavelEquipe: "Equipe Fresagem",
    responsavelColaboradorId: null,
    prioridade: 3,
    concluida: false,
    concluidaEm: null,
    createdAt: "2026-07-20T10:00:00.000Z",
    updatedAt: "2026-07-20T10:00:00.000Z",
    versaoEntidade: null,
    syncStatus: "PENDING_SYNC",
    deletadaEm: null,
    ...overrides,
  };
}

function remoteTaskPayload(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    id: TASK_ID,
    obraId: OBRA_ID,
    equipe: "Fresagem",
    titulo: "Tarefa remota",
    observacoes: "Encerrada em outro dispositivo.",
    criadaPor: "Encarregada remota",
    criadaPorColaboradorId: USER_ID,
    responsavelEquipe: "Equipe Fresagem",
    responsavelColaboradorId: null,
    prioridade: 2,
    concluida: true,
    concluidaEm: "2026-07-20T12:00:00.000Z",
    createdAt: "2026-07-20T10:00:00.000Z",
    updatedAt: "2026-07-20T12:00:00.000Z",
    deletadaEm: "2026-07-20T12:00:00.000Z",
    ...overrides,
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  vi.stubGlobal("BroadcastChannel", undefined);
  setSession({
    colaboradorId: USER_ID,
    nome: "Encarregada de campo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(
    USER_ID,
    `BETA:${OBRA_ID}`,
  );
  const database = await getCortexDb();
  await database.put("sync_state", {
    key: "default",
    deviceId: null,
    usuarioId: USER_ID,
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
    isSyncing: false,
    lastSyncStartedAt: null,
    lastSyncCompletedAt: null,
    lastSyncError: null,
    syncExecutionLease: null,
  });
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) {
    await deleteDB(databaseName);
  }
  clearSession();
  vi.unstubAllGlobals();
});

describe("convergência de TAREFA no sync storage", () => {
  it("materializa no pull uma tarefa remota com versão e tombstone", async () => {
    await applyPulledEventsAtomically(
      [
        {
          commitSeq: 41,
          eventoId: "00000000-0000-4000-8000-000000000198",
          tipoEvento: "TAREFA_EXCLUIDA",
          entidadeTipo: "TAREFA",
          entidadeId: TASK_ID,
          versaoEntidade: 3,
          payload: remoteTaskPayload(),
        },
      ],
      41,
    );

    const database = await getCortexDb();
    const stored = (await database.get(
      "tarefas",
      TASK_ID,
    )) as ConvergentTarefaRecord | undefined;

    expect(stored).toMatchObject({
      id: TASK_ID,
      versaoEntidade: 3,
      syncStatus: "SYNCED",
      concluida: true,
      deletadaEm: "2026-07-20T12:00:00.000Z",
    });
  });

  it("não substitui com o pull uma edição local ainda pendente", async () => {
    const database = await getCortexDb();
    await database.put("tarefas", task());

    await applyPulledEventsAtomically(
      [
        {
          commitSeq: 42,
          eventoId: "00000000-0000-4000-8000-000000000197",
          tipoEvento: "TAREFA_CRIADA",
          entidadeTipo: "TAREFA",
          entidadeId: TASK_ID,
          versaoEntidade: 8,
          payload: remoteTaskPayload({
            titulo:
              "Versão remota que não pode substituir o rascunho",
            concluida: false,
            concluidaEm: null,
            deletadaEm: null,
          }),
        },
      ],
      42,
    );

    const stored = (await database.get(
      "tarefas",
      TASK_ID,
    )) as ConvergentTarefaRecord | undefined;

    expect(stored).toMatchObject({
      titulo: "Registro local pendente",
      syncStatus: "PENDING_SYNC",
      versaoEntidade: null,
    });
  });

  it("recupera a fila interrompida sem descartar a edição local da tarefa", async () => {
    const database = await getCortexDb();
    await database.put(
      "tarefas",
      task({
        titulo: "Edição local durante a interrupção",
        syncStatus: "SYNCING",
        versaoEntidade: 2,
      }),
    );
    await database.put(
      "outbox_mutations",
      {
        clientMutationId: MUTATION_ID,
        entidadeTipo: "TAREFA",
        entidadeId: TASK_ID,
        operacao: "CONCLUIR_TAREFA",
        baseVersao: 2,
        payload: {
          id: TASK_ID,
          titulo: "Edição local durante a interrupção",
        },
        status: "SYNCING",
        tentativas: 1,
        ultimaTentativaEm: "2026-07-20T12:00:00.000Z",
        ultimoErro: null,
        conflito: null,
        criadaNoClienteEm: "2026-07-20T11:59:00.000Z",
        updatedAt: "2026-07-20T12:00:00.000Z",
      } as unknown as OutboxMutationRecord,
    );

    await recoverInterruptedMutations();

    const stored = (await database.get(
      "tarefas",
      TASK_ID,
    )) as ConvergentTarefaRecord | undefined;

    expect(
      await database.get("outbox_mutations", MUTATION_ID),
    ).toMatchObject({
      status: "PENDING",
    });
    expect(stored).toMatchObject({
      titulo: "Edição local durante a interrupção",
      syncStatus: "PENDING_SYNC",
      versaoEntidade: 2,
    });
  });
});
