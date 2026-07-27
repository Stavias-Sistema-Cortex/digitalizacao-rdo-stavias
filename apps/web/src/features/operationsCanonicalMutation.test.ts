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
  closeCortexDb,
  getCortexDb,
} from "../lib/db/cortexDb";
import { databaseNameForScope } from "../lib/db/localDataNamespace";
import {
  clearSession,
  setSession,
} from "./auth/authSession";
import { queueCreateTeam } from "./equipes/teamLocalRepository";
import { solicitarIntegracao } from "./integracoes/integracoesApi";
import { queueVinculoColaborador } from "./obras/gestao/gestaoObrasApi";

const OBRA_ID = "00000000-0000-4000-8000-000000000001";
const COLABORADOR_ID = "00000000-0000-4000-8000-000000000002";

let ownerId = "";

async function resetDatabase(): Promise<void> {
  await closeCortexDb();
  if (ownerId) {
    await deleteDB(await databaseNameForScope(ownerId, "ALFA:GLOBAL"));
  }
}

beforeEach(() => {
  ownerId = crypto.randomUUID();
  setSession({
    colaboradorId: ownerId,
    nome: "Administradora Alfa",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
});

afterEach(async () => {
  vi.unstubAllGlobals();
  await resetDatabase();
  clearSession();
});

describe("operações canônicas institucionais", () => {
  it("persiste equipe, outbox e evento numa única mutação local", async () => {
    const created = await queueCreateTeam({
      id: crypto.randomUUID(),
      obraId: OBRA_ID,
      obraNome: "Contorno Norte",
      nome: "Drenagem",
      descricao: null,
      inicioValidadeEm: "2026-07-25T00:00:00",
    });

    const database = await getCortexDb();
    const queued = await database.getAll("outbox_mutations");
    const events = await database.getAll("operational_events");

    expect(await database.get("teams", created.id)).toEqual(created);
    expect(queued).toHaveLength(1);
    expect(queued[0]).toMatchObject({
      entidadeTipo: "EQUIPE",
      entidadeId: created.id,
      operacao: "CRIAR_EQUIPE",
      status: "PENDING",
    });
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      type: "EQUIPE_CRIADA",
      principalEntity: { tipo: "EQUIPE", id: created.id },
      syncStatus: "PENDING_SYNC",
    });
  });

  it("projeta vínculo de obra imediatamente e o deixa para revalidação Alfa", async () => {
    const pending = await queueVinculoColaborador(
      OBRA_ID,
      COLABORADOR_ID,
      "Fiscalização",
    );

    const queued = await (await getCortexDb()).getAll("outbox_mutations");
    expect(pending).toMatchObject({
      obraId: OBRA_ID,
      colaboradorId: COLABORADOR_ID,
      status: "PENDENTE",
      syncStatus: "PENDING_SYNC",
    });
    expect(queued).toHaveLength(1);
    expect(queued[0]).toMatchObject({
      entidadeTipo: "VINCULO_OBRA",
      entidadeId: pending.id,
      operacao: "VINCULAR_COLABORADOR_OBRA",
      status: "PENDING",
    });
  });

  it("não simula ação externa: persiste PENDENTE/AGUARDANDO_REDE sem fetch", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    const request = await solicitarIntegracao("academy", "TESTAR");

    const database = await getCortexDb();
    const queued = await database.getAll("outbox_mutations");
    const events = await database.getAll("operational_events");

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(request).toMatchObject({
      integracaoId: "academy",
      acao: "TESTAR",
      estado: "PENDENTE",
      motivo: "AGUARDANDO_REDE",
    });
    expect(queued).toHaveLength(1);
    expect(queued[0]).toMatchObject({
      entidadeTipo: "SOLICITACAO_INTEGRACAO",
      operacao: "SOLICITAR_INTEGRACAO",
      status: "PENDING",
      obraId: null,
      payload: {
        estado: "PENDENTE",
        motivo: "AGUARDANDO_REDE",
      },
    });
    expect(events[0]).toMatchObject({
      type: "SOLICITACAO_INTEGRACAO_CRIADA",
      obraId: null,
      result: "PENDING",
    });
  });

  it("nega comando global a uma sessão Beta sem gravar estado parcial", async () => {
    setSession({
      colaboradorId: ownerId,
      nome: "Operadora Beta",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });

    await expect(
      solicitarIntegracao("academy", "SINCRONIZAR"),
    ).rejects.toThrow(/alfa|global/i);

    const database = await getCortexDb();
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
    expect(await database.getAll("operational_events")).toHaveLength(0);
  });
});
