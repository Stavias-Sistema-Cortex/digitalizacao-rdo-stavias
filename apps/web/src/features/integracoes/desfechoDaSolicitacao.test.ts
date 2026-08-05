import "fake-indexeddb/auto";

import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { deleteDB } from "idb";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
} from "../../lib/sync/syncStorage";
import { clearSession, setSession } from "../auth/authSession";
import {
  desfechoRecusado,
  listarDesfechosIntegracao,
  listarSolicitacoesIntegracaoPendentes,
  sincronizarIntegracao,
  testarConexaoIntegracao,
} from "./integracoesApi";

const ALFA_ID = "00000000-0000-4000-8000-0000000004a1";
const DEVICE_ID = "00000000-0000-4000-8000-0000000004a2";

let databaseName = "";

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  vi.stubGlobal("BroadcastChannel", undefined);
  setSession({
    colaboradorId: ALFA_ID,
    nome: "Administradora",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(ALFA_ID, "ALFA:GLOBAL");
  const database = await getCortexDb();
  await database.put("sync_state", {
    key: "default",
    deviceId: DEVICE_ID,
    usuarioId: ALFA_ID,
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
  vi.restoreAllMocks();
});

async function responder(
  entityId: string,
  resultado: Record<string, unknown>,
): Promise<void> {
  const database = await getCortexDb();
  const mutation = (await database.getAll("outbox_mutations")).find(
    (candidate) => candidate.entidadeId === entityId,
  );
  expect(mutation).toBeDefined();
  await markMutationAsSyncing(mutation!);
  await applyPushResultAtomically({
    clientMutationId: mutation!.clientMutationId,
    status: "APLICADA",
    entidadeTipo: "SOLICITACAO_INTEGRACAO",
    entidadeId: entityId,
    resultado,
  });
}

describe("desfecho da solicitação de integração", () => {
  /*
   * O servidor responde APLICADA ao recusar a sincronização: a mutação foi
   * processada, a ação é que não aconteceu. Antes, o recibo era descartado no
   * SYNCED e a solicitação apenas sumia da fila — a recusa ficava
   * indistinguível de um sucesso.
   */
  it("guarda a recusa DISABLED em vez de deixá-la sumir com o SYNCED", async () => {
    const pedido = await sincronizarIntegracao("academy");
    expect(await listarSolicitacoesIntegracaoPendentes()).toHaveLength(1);

    await responder(pedido.id, {
      id: pedido.id,
      integracaoId: "academy",
      acao: "SINCRONIZAR",
      estado: "DISABLED",
      mensagem: "Sincronização manual desativada neste ambiente.",
      executedAt: "2026-08-05T04:30:00Z",
    });

    expect(await listarSolicitacoesIntegracaoPendentes()).toHaveLength(0);
    expect(await listarDesfechosIntegracao()).toEqual([
      {
        id: pedido.id,
        integracaoId: "academy",
        acao: "SINCRONIZAR",
        estado: "DISABLED",
        mensagem: "Sincronização manual desativada neste ambiente.",
        executedAt: "2026-08-05T04:30:00Z",
        requestedAt: pedido.requestedAt,
      },
    ]);
  });

  it("trata DISABLED e FAILED como recusa, e não o que de fato rodou", () => {
    expect(desfechoRecusado("DISABLED")).toBe(true);
    expect(desfechoRecusado("FAILED")).toBe(true);
    expect(desfechoRecusado("SUCCESS")).toBe(false);
    expect(desfechoRecusado("PARTIAL")).toBe(false);
    // A ação começou; ainda não terminar não é ter sido recusada.
    expect(desfechoRecusado("RUNNING")).toBe(false);
  });

  it("guarda também o desfecho bem-sucedido, para não só noticiar recusa", async () => {
    const pedido = await testarConexaoIntegracao("zeladoria");
    await responder(pedido.id, {
      id: pedido.id,
      integracaoId: "zeladoria",
      acao: "TESTAR",
      estado: "SUCCESS",
      mensagem: "Conexão verificada.",
      executedAt: "2026-08-05T04:31:00Z",
    });

    const [desfecho] = await listarDesfechosIntegracao();
    expect(desfecho).toMatchObject({
      acao: "TESTAR",
      estado: "SUCCESS",
      mensagem: "Conexão verificada.",
    });
    expect(desfechoRecusado(desfecho.estado)).toBe(false);
  });

  it("ordena do mais recente para o mais antigo e respeita o limite", async () => {
    const primeiro = await testarConexaoIntegracao("academy");
    await responder(primeiro.id, {
      estado: "SUCCESS",
      mensagem: "Primeira.",
      executedAt: "2026-08-05T04:10:00Z",
    });
    const segundo = await sincronizarIntegracao("zeladoria");
    await responder(segundo.id, {
      estado: "DISABLED",
      mensagem: "Segunda.",
      executedAt: "2026-08-05T04:20:00Z",
    });

    expect(
      (await listarDesfechosIntegracao()).map((item) => item.mensagem),
    ).toEqual(["Segunda.", "Primeira."]);
    expect(await listarDesfechosIntegracao(1)).toHaveLength(1);
  });

  it("sobrevive a um recibo sem estado em vez de anunciar sucesso", async () => {
    const pedido = await sincronizarIntegracao("academy");
    await responder(pedido.id, {});

    const [desfecho] = await listarDesfechosIntegracao();
    expect(desfecho.estado).toBe("SEM_RESPOSTA");
    expect(desfecho.integracaoId).toBe("academy");
    expect(desfecho.executedAt).toBeNull();
  });
});

describe("a tela sem rede", () => {
  const api = readFileSync(
    resolve(process.cwd(), "./src/features/integracoes/integracoesApi.ts"),
    "utf8",
  );
  const page = readFileSync(
    resolve(process.cwd(), "./src/features/integracoes/IntegracoesPage.tsx"),
    "utf8",
  );

  it("não manda um administrador conferir CORS nem ligar a API local", () => {
    // A mensagem padrão do cliente HTTP é diagnóstico de desenvolvimento; esta
    // tela é de administração e precisa dizer o que dá para fazer.
    expect(api).toContain("connectionErrorMessage: SEM_REDE");
    expect(api).toContain("timeoutErrorMessage: DEMOROU");
    expect(api).toContain("Sem conexão com o servidor");
    expect(api).not.toContain("CORS");
  });

  it("não afirma que não há fontes quando não conseguiu consultar", () => {
    expect(page).toContain('"Não foi possível consultar as fontes."');
    expect(page).toContain('"Nenhuma integração encontrada."');
  });

  it("mostra o desfecho na tela, e não só na memória operacional", () => {
    expect(page).toContain("Desfecho das solicitações");
    expect(page).toContain("desfechoRecusado");
    expect(page).toContain("listarDesfechosIntegracao");
  });
});
