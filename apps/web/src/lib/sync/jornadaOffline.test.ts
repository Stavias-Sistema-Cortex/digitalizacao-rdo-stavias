import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiTransportError } from "../api/apiError";
import type {
  SyncPullEvent,
  SyncPushMutationRequest,
  SyncPushMutationResult,
} from "./sync.types";

/**
 * A rede é o único recurso que este cenário desliga.
 *
 * Tudo o mais roda de verdade: IndexedDB, coordenador canônico, outbox,
 * dependências entre mutações, push, pull e ack. Trocar o transporte por um
 * servidor de mentira é a única forma de rodar a jornada inteira num teste, e
 * ele é escrito contra o mesmo contrato que o Córtex expõe — quem mentir aqui
 * derruba a confiança no resto.
 */
const rede = vi.hoisted(() => {
  interface EntidadeServidor {
    versao: number;
    payload: Record<string, unknown>;
  }

  const entidades = new Map<string, EntidadeServidor>();
  const eventos: SyncPullEvent[] = [];
  const respostasPorMutacao = new Map<string, SyncPushMutationResult>();
  const recebidas: SyncPushMutationRequest[] = [];
  let commitSeq = 0;
  let ultimoAck = -1;

  const estado = {
    online: true,
    /*
     * O túnel, o morro, o 3G que some no meio da subida: o servidor grava e a
     * resposta nunca chega. É a queda mais comum em campo e a mais perigosa,
     * porque o aparelho não tem como distinguir "não gravou" de "gravou e não
     * respondeu" — e reenviar é a única saída possível.
     */
    engolirRespostaDoPush: false,
    recebidas,
    eventos,
    ackRecebido: () => ultimoAck,
    entidade: (tipo: string, id: string) => entidades.get(`${tipo}:${id}`),
    limpar(): void {
      entidades.clear();
      eventos.length = 0;
      respostasPorMutacao.clear();
      recebidas.length = 0;
      commitSeq = 0;
      ultimoAck = -1;
      estado.online = true;
      estado.engolirRespostaDoPush = false;
    },
    async responder(path: string, init?: RequestInit): Promise<Response> {
      if (!estado.online) {
        // É exatamente o que o navegador entrega quando não há rede, e é por
        // esse tipo que a fila decide entre "tenta de novo" e "recusada".
        throw new ApiTransportError(
          "Não foi possível falar com o Córtex. Os dados continuam salvos neste dispositivo.",
          "CONNECTION",
        );
      }
      const corpo = typeof init?.body === "string" ? JSON.parse(init.body) : null;

      if (path === "/sync/dispositivos") {
        return json({ id: corpo.id, nome: corpo.nome, tipo: "WEB", ativo: true });
      }

      if (path === "/sync/push") {
        const resultados = (corpo.mutacoes as SyncPushMutationRequest[]).map(
          (mutacao) => aplicar(mutacao),
        );
        if (estado.engolirRespostaDoPush) {
          // Gravado do lado de lá; do lado de cá, só o silêncio da rede.
          throw new ApiTransportError(
            "A conexão caiu antes da confirmação. O trabalho continua salvo neste dispositivo.",
            "CONNECTION",
          );
        }
        return json({
          resultados,
          serverCommitSeq: commitSeq,
          serverTimeUtc: new Date().toISOString(),
        });
      }

      if (path.startsWith("/sync/pull")) {
        const query = new URLSearchParams(path.slice(path.indexOf("?") + 1));
        const after = Number(query.get("afterCommitSeq") ?? 0);
        const limite = Number(query.get("limit") ?? 100);
        const pendentes = eventos.filter((evento) => evento.commitSeq > after);
        const pagina = pendentes.slice(0, limite);
        return json({
          eventos: pagina,
          nextCommitSeq: pagina.at(-1)?.commitSeq ?? after,
          hasMore: pendentes.length > pagina.length,
        });
      }

      if (path === "/sync/ack") {
        ultimoAck = corpo.ultimoEventoRecebidoCommitSeq;
        return json({
          dispositivoId: corpo.dispositivoId,
          ultimoEventoRecebidoCommitSeq: ultimoAck,
        });
      }

      throw new Error(`Rota não prevista no servidor de mentira: ${path}`);
    },
  };

  function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  }

  /**
   * O reenvio da mesma mutação devolve o mesmo veredito.
   *
   * Uma janela de sincronização pode morrer depois do servidor gravar e antes
   * da resposta chegar. Se o segundo envio criasse outra linha, uma queda de
   * rede viraria duplicata no histórico da obra.
   */
  function aplicar(mutacao: SyncPushMutationRequest): SyncPushMutationResult {
    recebidas.push(mutacao);
    const jaRespondida = respostasPorMutacao.get(mutacao.clientMutationId);
    if (jaRespondida) {
      return jaRespondida;
    }

    const chave = `${mutacao.entidadeTipo}:${mutacao.entidadeId}`;
    const existente = entidades.get(chave);
    const baseEsperada = existente?.versao ?? null;
    const baseEnviada = mutacao.baseVersao ?? null;

    if (baseEnviada !== baseEsperada) {
      const conflito: SyncPushMutationResult = {
        clientMutationId: mutacao.clientMutationId,
        status: "CONFLITO",
        entidadeTipo: mutacao.entidadeTipo,
        entidadeId: mutacao.entidadeId,
        conflito: {
          versaoServidor: baseEsperada,
          versaoEnviada: baseEnviada,
          servidor: existente?.payload ?? {},
        },
        erro: "A entidade mudou no servidor depois desta edição.",
      };
      respostasPorMutacao.set(mutacao.clientMutationId, conflito);
      return conflito;
    }

    const versao = (existente?.versao ?? 0) + 1;
    entidades.set(chave, {
      versao,
      payload: { ...(existente?.payload ?? {}), ...mutacao.payload },
    });
    commitSeq += 1;
    eventos.push({
      commitSeq,
      eventoId: `evt-${commitSeq}`,
      tipoEvento: `${mutacao.entidadeTipo}_${mutacao.operacao}`,
      entidadeTipo: mutacao.entidadeTipo,
      entidadeId: mutacao.entidadeId,
      ocorridoEmUtc: mutacao.criadaNoClienteEm,
      criadoEmUtc: new Date().toISOString(),
      versaoEntidade: versao,
      payload: null,
    });

    const aplicada: SyncPushMutationResult = {
      clientMutationId: mutacao.clientMutationId,
      status: "APLICADA",
      // O eco da identidade é o contrato que destravou a geometria: o servidor
      // confirma a entidade que recebeu, nunca uma que ele mesmo cunhou.
      entidadeTipo: mutacao.entidadeTipo,
      entidadeId: mutacao.entidadeId,
      eventoServidorCommitSeq: commitSeq,
      resultado: { id: mutacao.entidadeId, versaoEntidade: versao },
      conflito: null,
      erro: null,
    };
    respostasPorMutacao.set(mutacao.clientMutationId, aplicada);
    return aplicada;
  }

  return estado;
});

vi.mock("../api/apiClient", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("../api/apiClient")>();
  return {
    ...original,
    apiFetch: (path: string, options?: RequestInit) =>
      rede.responder(path, options),
  };
});

const janela = new EventTarget();
vi.stubGlobal("window", janela);
vi.stubGlobal("navigator", {
  onLine: true,
  platform: "Teste",
  userAgent: "Teste",
});

import {
  clearSession,
  setOfflineSession,
  setSession,
  type AuthProfile,
} from "../../features/auth/authSession";
import { queueCreateTeam } from "../../features/equipes/teamLocalRepository";
import {
  registrarPontoDeCampo,
  registrarTrechoDesenhado,
} from "../../features/obras/map/obraGeometriaMutations";
import { queueMessage } from "../../features/mensagens/mensagensRepository";
import { createAndPersistRdoDraft } from "../../features/rdos/rdoDraftCreation";
import { putRdoCreationContext } from "../../features/rdos/rdoCreationContextRepository";
import type { RdoCreationContextLookup } from "../../features/rdos/rdoLookupApi";
import type { ServicoExecutadoDraft } from "../../features/rdos/rdo.types";
import { carregarTrechoDaObra } from "../../features/obras/trecho/obraTrechoApi";
import {
  rdoDraftFromLocalRecord,
  saveRdoDraftAtomically,
} from "../db/localRdoService";
import { closeCortexDb, getCortexDb, initializeCortexDb } from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import { listOutboxMutations } from "../db/outboxRepository";
import { updateSyncState } from "../db/syncStateRepository";
import { createTarefa } from "../db/tarefaRepository";
import { syncNow } from "./syncEngine";
import { contarPendenciasDaFila } from "./useSyncStatus";
import { createAutomaticSyncScheduler } from "./automaticSyncScheduler";
import { loadEarliestAutomaticRetryAt } from "./automaticSyncRetryStorage";

const USUARIO_ID = "00000000-0000-4000-8000-0000000009a1";
const DISPOSITIVO_ID = "00000000-0000-4000-8000-0000000009a2";
const OBRA_ID = "00000000-0000-4000-8000-0000000009a3";
const CONVERSA_ID = "00000000-0000-4000-8000-0000000009a4";
const RDO_ID = "00000000-0000-4000-8000-0000000009a5";

function cobertura(total = 0) {
  return { status: "COMPLETE", total, returned: total, complete: true };
}

/** O recibo que o aparelho baixou antes de sair da base. */
function contextoDaObra(): RdoCreationContextLookup {
  return {
    obra: {
      id: OBRA_ID,
      codigoContrato: "CTR-101",
      codigoCw: "CW-101",
      nome: "Duplicação BR-101",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: "BR-101",
      status: "ATIVA",
      version: 4,
    },
    data: "2026-08-03",
    nextNumberSuggestion: "RDO-0042",
    previousRdo: null,
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
    serviceCatalog: [],
    coverage: {
      previousWorkforce: cobertura(),
      programacoes: cobertura(),
      colaboradores: cobertura(),
      equipamentos: cobertura(),
      serviceCatalog: {
        status: "NOT_CONFIGURED",
        total: 0,
        returned: 0,
        complete: false,
      },
      priceCatalog: {
        status: "NOT_CONFIGURED",
        total: 0,
        returned: 0,
        complete: false,
      },
    },
    freshness: {
      status: "FRESH",
      sourceVersion: 5,
      generatedAt: "2026-08-03T11:00:00.000Z",
      staleAfter: "2026-08-03T23:00:00.000Z",
    },
    provenance: {
      receiptVersion: 7,
      sourceVersion: 5,
      worksiteId: OBRA_ID,
      selectedDate: "2026-08-03",
      previousRdoId: null,
      generatedAt: "2026-08-03T11:00:00.000Z",
    },
  };
}

let nomeDoBanco = "";
let perfil: AuthProfile;

function desligarRede(): void {
  rede.online = false;
  (navigator as unknown as { onLine: boolean }).onLine = false;
}

function ligarRede(): void {
  rede.online = true;
  (navigator as unknown as { onLine: boolean }).onLine = true;
}

beforeEach(async () => {
  rede.limpar();
  ligarRede();
  perfil = {
    colaboradorId: USUARIO_ID,
    nome: "Encarregada de campo",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: new Date(Date.now() + 3_600_000).toISOString(),
  };
  setSession(perfil);
  nomeDoBanco = await databaseNameForScope(USUARIO_ID, "ALFA:GLOBAL");
  await initializeCortexDb();
  await updateSyncState({ deviceId: DISPOSITIVO_ID, usuarioId: USUARIO_ID });
  await putRdoCreationContext(contextoDaObra());
});

afterEach(async () => {
  await closeCortexDb();
  if (nomeDoBanco) await deleteDB(nomeDoBanco);
  clearSession();
});

function servicoExecutado(): ServicoExecutadoDraft {
  return {
    localId: "servico-1",
    serviceId: "",
    priceVersionId: "",
    servicoNome: "Compactação do aterro",
    itemContratualId: "",
    quantidadeExecutada: "120",
    unidade: "m3",
    trechoInicial: "12+000",
    trechoFinal: "12+800",
    pista: "SIMPLES",
    faixa: "1",
    localizacao: "Lado direito",
    turno: "",
    statusValidacao: "REGISTRADA",
    retrabalho: false,
    producaoRejeitada: false,
    observacoes: "",
  };
}

/** Um dia inteiro de campo sem sinal, seguido do retorno da conexão. */
async function jornadaSemSinal(): Promise<{
  equipeId: string;
  trechoId: string;
  pontoId: string;
  tarefaId: string;
  mensagemId: string;
  rdoId: string;
}> {
  // O RDO do dia nasce do recibo baixado na base e é salvo de novo a cada
  // campo preenchido. É a escrita mais frequente do app e a que mais dói
  // perder: o apontamento do dia inteiro cabe nessas gravações.
  await createAndPersistRdoDraft(contextoDaObra(), {
    draftId: RDO_ID,
    occurredAt: "2026-08-03T12:00:00.000Z",
  });
  const banco = await getCortexDb();
  for (const observacao of [
    "Frente iniciada às 7h.",
    "Frente iniciada às 7h. Chuva das 10h às 11h30.",
    "Frente iniciada às 7h. Chuva das 10h às 11h30. Retomada às 12h.",
  ]) {
    const atual = await banco.get("rdos", RDO_ID);
    await saveRdoDraftAtomically({
      ...rdoDraftFromLocalRecord(atual!),
      observacoes: observacao,
      // O serviço apontado é o que o trecho tem de desenhar hoje.
      servicosExecutados: [servicoExecutado()],
    });
  }

  const equipe = await queueCreateTeam({
    id: crypto.randomUUID(),
    obraId: OBRA_ID,
    obraNome: "Duplicação BR-101",
    nome: "Equipe de pavimentação",
    descricao: "Turno da manhã",
    inicioValidadeEm: new Date().toISOString(),
  });

  const trecho = await registrarTrechoDesenhado({
    obraId: OBRA_ID,
    // O desenho pertence ao apontamento do dia: é o que faz o trecho sumir
    // quando o RDO é apagado.
    rdoId: RDO_ID,
    pontos: [
      { lat: -23.55, lng: -46.63 },
      { lat: -23.56, lng: -46.64 },
    ],
  });

  const ponto = await registrarPontoDeCampo({
    obraId: OBRA_ID,
    objetoTipo: "OBRA",
    objetoId: OBRA_ID,
    latitude: -23.5505,
    longitude: -46.6333,
    precisaoM: 4.2,
  });

  const tarefa = await createTarefa(
    {
      obraId: OBRA_ID,
      equipe: "Pavimentação",
      titulo: "Conferir a compactação da camada",
      observacoes: "Medir antes da liberação do tráfego.",
      criadaPor: perfil.nome,
      criadaPorColaboradorId: USUARIO_ID,
      responsavelEquipe: "Pavimentação",
      responsavelColaboradorId: USUARIO_ID,
      prioridade: "ALTA",
    },
    { colaboradorId: USUARIO_ID, nome: perfil.nome },
  );

  const mensagem = await queueMessage({
    conversaId: CONVERSA_ID,
    corpo: "Frente liberada, seguimos amanhã do km 12.",
    files: [],
  });

  return {
    equipeId: equipe.id,
    trechoId: trecho.id,
    pontoId: ponto.id,
    tarefaId: tarefa.id,
    mensagemId: mensagem.id,
    rdoId: RDO_ID,
  };
}

/** O mesmo agendador que o app monta, com a rede do teste como mundo. */
function agendadorDeTeste(
  onRun?: (gatilho: string) => void,
) {
  return createAutomaticSyncScheduler({
    syncNow,
    hasOnlineSession: () => true,
    loadNextRetryAt: loadEarliestAutomaticRetryAt,
    isOnline: () => rede.online,
    eventTarget: janela,
    visibilityTarget: new EventTarget(),
    getVisibilityState: () => "visible",
    intervalMs: 500,
    onRun,
  });
}

describe("jornada offline do Córtex", () => {
  it("guarda o dia inteiro de campo sem rede e sem perder nada", async () => {
    desligarRede();
    const registros = await jornadaSemSinal();
    const banco = await getCortexDb();

    // O trabalho existe no aparelho antes de qualquer rede: é isso que a
    // pessoa vê na tela quando volta a abrir o app no meio da obra.
    expect(await banco.get("teams", registros.equipeId)).toMatchObject({
      nome: "Equipe de pavimentação",
      syncStatus: "PENDING_SYNC",
    });
    expect(await banco.get("obra_geometrias", registros.trechoId)).toMatchObject(
      { categoria: "TRECHO", syncStatus: "PENDING_SYNC" },
    );
    expect(await banco.get("obra_geometrias", registros.pontoId)).toMatchObject(
      { categoria: "PONTO_OPERACIONAL", syncStatus: "PENDING_SYNC" },
    );
    expect(await banco.get("tarefas", registros.tarefaId)).toBeTruthy();
    expect(await banco.get("mensagens", registros.mensagemId)).toMatchObject({
      syncStatus: "NA_FILA",
    });

    // Cada escrita canônica deixa sua evidência na ontologia no mesmo instante,
    // não quando o servidor confirma.
    const eventos = await banco.getAll("operational_events");
    expect(eventos.length).toBeGreaterThanOrEqual(8);

    /*
     * Salvar o RDO três vezes em campo não é falha nenhuma. Cada gravação
     * absorve a anterior, e o evento da absorvida precisa dizer isso — dizia
     * "recusada / falha de sincronização", com o aparelho em modo avião o dia
     * inteiro, sem ter falado com servidor nenhum.
     */
    const naoEnviados = eventos.filter(
      (evento) => evento.syncStatus === "SYNC_FAILED",
    );
    expect(naoEnviados).toEqual([]);
    expect(eventos.filter((evento) => evento.result === "REJECTED")).toEqual([]);
    expect(
      eventos.filter((evento) => evento.result === "SUPERSEDED"),
    ).toHaveLength(3);
    expect(
      eventos.filter((evento) => evento.result === "PENDING").length,
    ).toBeGreaterThanOrEqual(5);

    // Nenhuma tentativa de rede foi feita.
    expect(rede.recebidas).toHaveLength(0);
    const fila = await listOutboxMutations();
    // RDO, equipe, trecho, ponto de campo, tarefa e mensagem: uma linha viva
    // por assunto, com as três gravações intermediárias do RDO já absorvidas.
    expect(
      fila.filter((linha) => linha.status === "PENDING"),
    ).toHaveLength(6);
  });

  it("sincroniza tudo sozinho quando a conexão volta, sem conflito nem erro", async () => {
    desligarRede();
    const registros = await jornadaSemSinal();

    // A volta da conexão é o único gesto — e ele é do mundo, não da pessoa.
    ligarRede();
    const resumo = await syncNow();

    expect(resumo.errors).toBe(0);
    expect(resumo.conflicts).toBe(0);
    expect(resumo.applied).toBeGreaterThanOrEqual(5);

    // Nada sobra pedindo atenção — lido com a mesma régua da tarja de sync.
    expect(
      contarPendenciasDaFila(await listOutboxMutations()),
    ).toEqual({
      pendingCount: 0,
      syncingCount: 0,
      errorCount: 0,
      conflictCount: 0,
      reviewCount: 0,
    });

    // O servidor guarda cada entidade sob a identidade que o aparelho cunhou.
    expect(rede.entidade("EQUIPE", registros.equipeId)).toMatchObject({
      versao: 1,
    });
    expect(
      rede.entidade("GEOMETRIA_OBRA", registros.trechoId),
    ).toMatchObject({ versao: 1 });
    expect(rede.entidade("GEOMETRIA_OBRA", registros.pontoId)).toMatchObject({
      versao: 1,
    });
    expect(rede.entidade("TAREFA", registros.tarefaId)).toMatchObject({
      versao: 1,
    });
    expect(rede.entidade("MENSAGEM", registros.mensagemId)).toMatchObject({
      versao: 1,
    });

    const banco = await getCortexDb();
    const eventos = await banco.getAll("operational_events");
    expect(eventos.some((evento) => evento.result === "PENDING")).toBe(false);
  });

  it("não duplica nada quando a rede cai no meio do envio e o ciclo repete", async () => {
    desligarRede();
    const registros = await jornadaSemSinal();
    ligarRede();

    await syncNow();
    // A segunda janela não tem o que enviar; se enviasse, o servidor de mentira
    // devolveria o mesmo veredito e a versão não subiria.
    await syncNow();

    expect(rede.entidade("EQUIPE", registros.equipeId)).toMatchObject({
      versao: 1,
    });
    expect(rede.entidade("TAREFA", registros.tarefaId)).toMatchObject({
      versao: 1,
    });
    expect(
      contarPendenciasDaFila(await listOutboxMutations()),
    ).toMatchObject({ pendingCount: 0, errorCount: 0, conflictCount: 0, reviewCount: 0 });
  });

  it("recupera sozinho quando a resposta do servidor se perde na volta", async () => {
    desligarRede();
    const registros = await jornadaSemSinal();

    // Sinal de volta, mas fraco: o servidor grava e a resposta não chega.
    ligarRede();
    rede.engolirRespostaDoPush = true;
    await expect(syncNow()).rejects.toThrow();

    // O trabalho ficou gravado lá; aqui ninguém sabe disso ainda.
    expect(rede.entidade("TAREFA", registros.tarefaId)).toMatchObject({
      versao: 1,
    });
    const durante = contarPendenciasDaFila(await listOutboxMutations());
    expect(durante.errorCount + durante.pendingCount).toBeGreaterThan(0);
    expect(durante.conflictCount).toBe(0);

    /*
     * O reenvio é agendado sozinho, com espera escalonada. Sem esse
     * agendamento a fila ficaria parada até alguém abrir o app e apertar
     * "sincronizar" — que é exatamente a intervenção humana que não pode
     * existir.
     */
    const reenvioMarcadoPara = await loadEarliestAutomaticRetryAt();
    expect(reenvioMarcadoPara).not.toBeNull();

    // A janela agendada reenvia o mesmo envelope. O servidor reconhece a
    // identidade e devolve o mesmo veredito: nada duplica, nada conflita.
    rede.engolirRespostaDoPush = false;
    const agendador = agendadorDeTeste();
    agendador.start();
    await vi.waitFor(
      async () => {
        expect(
          contarPendenciasDaFila(await listOutboxMutations()),
        ).toMatchObject({
          pendingCount: 0,
          errorCount: 0,
          conflictCount: 0,
          reviewCount: 0,
        });
      },
      { timeout: 15_000, interval: 100 },
    );
    agendador.dispose();

    // Uma linha por assunto no servidor: o reenvio não criou uma segunda.
    expect(rede.entidade("TAREFA", registros.tarefaId)).toMatchObject({
      versao: 1,
    });
    expect(rede.entidade("EQUIPE", registros.equipeId)).toMatchObject({
      versao: 1,
    });
    expect(rede.entidade("RDO", registros.rdoId)).toMatchObject({ versao: 1 });
  }, 20_000);

  /**
   * O aparelho reaberto no meio da obra roda em sessão offline, destrancada
   * pelo cofre local — não é a sessão online sem rede. É outro caminho: o
   * transporte se recusa a sair antes mesmo de tentar, e nada além do
   * IndexedDB responde. Se as escritas dependessem do que só a sessão online
   * tem, o dia inteiro de apontamento morreria na primeira tela.
   */
  it("aceita o dia de trabalho com a sessão destrancada pelo cofre local", async () => {
    desligarRede();
    setOfflineSession(perfil);

    const registros = await jornadaSemSinal();
    const banco = await getCortexDb();

    expect(await banco.get("obra_geometrias", registros.pontoId)).toBeTruthy();
    expect(await banco.get("tarefas", registros.tarefaId)).toBeTruthy();
    const rdo = await banco.get("rdos", registros.rdoId);
    expect(rdoDraftFromLocalRecord(rdo!).observacoes).toBe(
      "Frente iniciada às 7h. Chuva das 10h às 11h30. Retomada às 12h.",
    );
    expect(
      contarPendenciasDaFila(await listOutboxMutations()).pendingCount,
    ).toBe(6);

    // Sem sessão online não se sincroniza, e a recusa é limpa: nada da fila
    // muda de estado por causa dela.
    await expect(syncNow()).rejects.toThrow();
    expect(rede.recebidas).toHaveLength(0);
    expect(
      contarPendenciasDaFila(await listOutboxMutations()),
    ).toMatchObject({ pendingCount: 6, errorCount: 0, conflictCount: 0 });

    // De volta à base: o login online reabre o transporte e a fila sobe
    // inteira, sem que ninguém tenha de repetir gesto nenhum.
    ligarRede();
    setSession(perfil);
    const resumo = await syncNow();

    expect(resumo.errors).toBe(0);
    expect(resumo.conflicts).toBe(0);
    expect(
      contarPendenciasDaFila(await listOutboxMutations()),
    ).toMatchObject({
      pendingCount: 0,
      errorCount: 0,
      conflictCount: 0,
      reviewCount: 0,
    });
    expect(rede.entidade("RDO", registros.rdoId)).toMatchObject({ versao: 1 });
  });

  /**
   * A mensagem entra na fila pela escrita direta, sem passar pelo coordenador
   * canônico, e por isso era a única escrita local que não avisava o
   * agendador: dependia do `syncNow` explícito da tela de Mensagens para não
   * ficar esperando a janela de trinta segundos. Toda escrita local acorda o
   * sync — esta também.
   */
  it("a mensagem enfileirada acorda o sync como qualquer outra escrita local", async () => {
    const execucoes: string[] = [];
    const agendador = agendadorDeTeste((gatilho) => execucoes.push(gatilho));
    agendador.start();
    execucoes.length = 0;

    await queueMessage({
      conversaId: CONVERSA_ID,
      corpo: "Chegamos no km 12.",
      files: [],
    });

    await vi.waitFor(() => {
      expect(execucoes).toContain("LOCAL_WRITE");
    });
    agendador.dispose();
  });

  /**
   * O trecho precisa desenhar o trabalho de hoje, não o de ontem.
   *
   * A projeção autoritativa vem do servidor, e sem rede não vem nada. Se a
   * leitura parasse aí, quem está em campo abriria o trecho e veria a obra
   * como ela estava na última sincronização — sem o que acabou de apontar.
   */
  it("desenha o trecho com o que foi apontado neste aparelho", async () => {
    desligarRede();
    await jornadaSemSinal();

    const leitura = await carregarTrechoDaObra({
      id: OBRA_ID,
      nome: "Duplicação BR-101",
      operavel: true,
    });

    expect(leitura.origem).toBe("DISPOSITIVO");
    const segmentos = leitura.projecao.segmentos ?? [];
    expect(segmentos).toContainEqual(
      expect.objectContaining({
        servicoNome: "Compactação do aterro",
        kmInicial: 12,
        kmFinal: 12.8,
        procedencia: "DISPOSITIVO",
      }),
    );
  });

  it("o retorno da conexão dispara a sincronização sem ninguém apertar nada", async () => {
    desligarRede();
    await jornadaSemSinal();

    const execucoes: string[] = [];
    const agendador = agendadorDeTeste((gatilho) => execucoes.push(gatilho));
    agendador.start();

    // Offline o agendador não bate na porta fechada.
    expect(execucoes).toEqual([]);

    ligarRede();
    janela.dispatchEvent(new Event("online"));
    await vi.waitFor(async () => {
      expect(
        contarPendenciasDaFila(await listOutboxMutations()),
      ).toMatchObject({ pendingCount: 0, errorCount: 0, conflictCount: 0, reviewCount: 0 });
    });

    expect(execucoes).toContain("ONLINE");
    agendador.dispose();
  });
});
