import type { ObraGeometriaLocalRecord } from "../../../lib/db/db.types";
import { getSyncState, updateSyncState } from "../../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../../lib/sync/localMutationCoordinator";
import { getSession } from "../../auth/authSession";
import { lerGeometriaLocal } from "./obraGeoCacheRepository";
import type { PontoGeografico } from "./LeafletTrechoMap";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

interface MutationIdentity {
  userId: string;
  deviceId: string;
}

async function geometriaMutationIdentity(): Promise<MutationIdentity> {
  const session = getSession();
  if (!session) {
    throw new Error("Sessão válida obrigatória para registrar geometria.");
  }
  const state = await getSyncState();
  const deviceId =
    state.usuarioId === session.colaboradorId &&
      state.deviceId &&
      UUID_PATTERN.test(state.deviceId)
      ? state.deviceId
      : crypto.randomUUID();
  if (state.deviceId !== deviceId || state.usuarioId !== session.colaboradorId) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }
  return { userId: session.colaboradorId, deviceId };
}

function transportSnapshot(
  registro: ObraGeometriaLocalRecord,
): Record<string, unknown> {
  return {
    id: registro.id,
    obraId: registro.obraId,
    categoria: registro.categoria,
    objetoTipo: registro.objetoTipo,
    objetoId: registro.objetoId,
    geometry: registro.geometry,
    properties: registro.properties,
    fonte: registro.fonte,
    validoDesde: registro.validoDesde,
    validoAte: registro.validoAte,
  };
}

interface NovaGeometriaInput {
  obraId: string;
  categoria: string;
  objetoTipo: string;
  objetoId: string;
  geometry: unknown;
  properties?: Record<string, unknown>;
  fonte: string;
}

/**
 * Tipos que o sync sabe resolver como entidade relacionada.
 *
 * `objetoTipo` descreve o que a geometria representa, e nem todo valor é uma
 * entidade persistida: "TRECHO" é uma categoria de desenho, sem tabela e sem
 * obra própria para o servidor conferir. Mandá-la como entidade relacionada faz
 * o push inteiro ser recusado, então o assunto do desenho viaja apenas no
 * payload (`objetoTipo`/`objetoId`), que é onde a ontologia o lê.
 */
const RELACAO_CANONICA_DO_OBJETO: ReadonlySet<string> = new Set([
  "OBRA",
  "RDO",
  "TAREFA",
]);

function entidadesRelacionadasDaGeometria(
  input: NovaGeometriaInput,
): { tipo: string; id: string }[] {
  const relacoes = [{ tipo: "OBRA", id: input.obraId }];
  if (
    RELACAO_CANONICA_DO_OBJETO.has(input.objetoTipo) &&
    input.objetoId !== input.obraId
  ) {
    relacoes.push({ tipo: input.objetoTipo, id: input.objetoId });
  }
  return relacoes;
}

async function enfileirarNovaGeometria(
  input: NovaGeometriaInput,
  transportOperation: "REGISTRAR_GEOMETRIA_OBRA" | "REGISTRAR_GEOMETRIA_CAMPO",
): Promise<ObraGeometriaLocalRecord> {
  const identity = await geometriaMutationIdentity();
  const agora = new Date().toISOString();
  const registro: ObraGeometriaLocalRecord = {
    id: crypto.randomUUID(),
    ownerId: identity.userId,
    obraId: input.obraId,
    categoria: input.categoria,
    objetoTipo: input.objetoTipo,
    objetoId: input.objetoId,
    geometry: input.geometry,
    properties: input.properties ?? {},
    fonte: input.fonte,
    status: "ATIVA",
    validoDesde: agora,
    validoAte: null,
    versao: 0,
    syncStatus: "PENDING_SYNC",
    fetchedAt: null,
    updatedAt: agora,
  };

  await commitLocalMutation({
    ...identity,
    obraId: input.obraId,
    entityType: "GEOMETRIA_OBRA",
    entityId: registro.id,
    entityName: input.categoria,
    operation: "CREATE",
    transportOperation,
    baseVersion: null,
    occurredAt: agora,
    previousSnapshot: {},
    nextSnapshot: transportSnapshot(registro),
    principalSnapshot: { ...registro },
    expectedPrincipalSnapshot: null,
    eventType: "GEOMETRIA_CRIADA",
    colaboradorId: identity.userId,
    relatedEntities: entidadesRelacionadasDaGeometria(input),
    write: () => [{ store: "obra_geometrias", value: registro, principal: true }],
  });

  return registro;
}

/**
 * Registra o trecho desenhado pelo Alfa sobre o mapa.
 *
 * A geometria é a linha que a pessoa realmente marcou; nenhum ponto é
 * interpolado. A escrita é local e atômica com sua evidência, e sobe sozinha na
 * próxima janela de sincronização.
 */
export async function registrarTrechoDesenhado(input: {
  obraId: string;
  /** RDO do dia que este desenho representa. */
  rdoId: string;
  pontos: readonly PontoGeografico[];
  propriedades?: Record<string, unknown>;
}): Promise<ObraGeometriaLocalRecord> {
  if (input.pontos.length < 2) {
    throw new Error("Um trecho exige ao menos o ponto inicial e o final.");
  }
  if (!input.rdoId.trim()) {
    throw new Error("Um trecho desenhado pertence ao RDO do dia.");
  }
  return enfileirarNovaGeometria(
    {
      obraId: input.obraId,
      categoria: "TRECHO",
      // Era "TRECHO" apontando para a própria obra: uma auto-referência, sem
      // entidade do outro lado. O desenho não sabia de que dia nem de que
      // apontamento falava, então sobrevivia ao RDO que representava e podia
      // declarar uma rodovia que ninguém conferia. Desenhar e apontar são duas
      // portas para o mesmo registro.
      objetoTipo: "RDO",
      objetoId: input.rdoId,
      geometry: {
        type: "LineString",
        coordinates: input.pontos.map((ponto) => [ponto.lng, ponto.lat]),
      },
      properties: input.propriedades,
      fonte: "GESTAO_MAPA",
    },
    "REGISTRAR_GEOMETRIA_OBRA",
  );
}

/**
 * Registra a posição observada em campo pela PWA.
 *
 * Guarda a precisão informada pelo dispositivo junto do ponto, para que a
 * leitura posterior saiba o quanto confiar naquela coordenada.
 */
export async function registrarPontoDeCampo(input: {
  obraId: string;
  objetoTipo: string;
  objetoId: string;
  latitude: number;
  longitude: number;
  precisaoM?: number | null;
  observadoEm?: string;
}): Promise<ObraGeometriaLocalRecord> {
  if (
    !Number.isFinite(input.latitude) ||
    !Number.isFinite(input.longitude) ||
    Math.abs(input.latitude) > 90 ||
    Math.abs(input.longitude) > 180
  ) {
    throw new Error("Coordenada de campo fora do intervalo geográfico válido.");
  }
  return enfileirarNovaGeometria(
    {
      obraId: input.obraId,
      categoria: "PONTO_OPERACIONAL",
      objetoTipo: input.objetoTipo,
      objetoId: input.objetoId,
      geometry: {
        type: "Point",
        coordinates: [
          Number(input.longitude.toFixed(6)),
          Number(input.latitude.toFixed(6)),
        ],
      },
      properties: {
        precisaoM: input.precisaoM ?? null,
        observadoEm: input.observadoEm ?? new Date().toISOString(),
      },
      fonte: "CAPTURA_CAMPO",
    },
    "REGISTRAR_GEOMETRIA_CAMPO",
  );
}

/**
 * Geometria que a tela já tem em mãos, para o caso de o dispositivo não a ter.
 *
 * <p>O mapa desenha o que veio do servidor mesmo quando a gravação local não
 * aconteceu — a reconciliação com o IndexedDB é tolerante a falha de
 * propósito, para o mapa não sumir por causa dela. O efeito colateral era
 * cruel: a geometria aparecia na tela, alguém mandava encerrá-la, e o pedido
 * morria em "não encontrada neste dispositivo" contra um ponto que estava
 * visível ali. Quem clica na lixeira já provou que a geometria existe.
 */
export interface GeometriaVisivelNoMapa {
  id: string;
  obraId: string;
  categoria: string;
  objetoTipo: string | null;
  objetoId: string | null;
  geometry: unknown;
  properties: Record<string, unknown>;
  fonte: string;
  versao: number;
  validoDesde: string;
}

function registroApartirDoMapa(
  visivel: GeometriaVisivelNoMapa,
  ownerId: string,
  agora: string,
): ObraGeometriaLocalRecord {
  return {
    id: visivel.id,
    ownerId,
    obraId: visivel.obraId,
    categoria: visivel.categoria,
    objetoTipo: visivel.objetoTipo,
    objetoId: visivel.objetoId,
    geometry: visivel.geometry,
    properties: visivel.properties,
    fonte: visivel.fonte,
    status: "ATIVA",
    validoDesde: visivel.validoDesde,
    validoAte: null,
    versao: visivel.versao,
    syncStatus: "SYNCED",
    fetchedAt: agora,
    updatedAt: agora,
  } as ObraGeometriaLocalRecord;
}

/**
 * Encerra a vigência de uma geometria, preservando o histórico.
 *
 * <p>Aceita a geometria que a tela está mostrando como segunda fonte. Antes
 * exigia que o registro estivesse no dispositivo e com a sincronização em dia,
 * e as duas exigências transformavam estados normais em becos sem saída: uma
 * gravação local que não aconteceu tornava o ponto impossível de remover, e um
 * envio que falhou uma vez deixava o registro fora de `SYNCED` para sempre —
 * a reconciliação com o servidor nunca devolve esse estado. A mensagem mandava
 * aguardar uma sincronização que jamais chegaria, e o ponto ficava no mapa.
 *
 * <p>A única condição que sobra é a que importa de verdade: o servidor precisa
 * conhecer a geometria. Um desenho que nunca subiu não tem o que encerrar lá.
 */
export async function encerrarGeometria(
  featureId: string,
  motivo: string,
  visivelNoMapa?: GeometriaVisivelNoMapa,
): Promise<ObraGeometriaLocalRecord> {
  const razao = motivo.trim();
  if (!razao) {
    throw new Error("Motivo do encerramento obrigatório.");
  }

  const identity = await geometriaMutationIdentity();
  const local = await lerGeometriaLocal(featureId);
  const agoraParaFallback = new Date().toISOString();
  const existing =
    local ??
    (visivelNoMapa && visivelNoMapa.id === featureId
      ? registroApartirDoMapa(visivelNoMapa, identity.userId, agoraParaFallback)
      : null);
  if (!existing) {
    throw new Error("Geometria não encontrada neste dispositivo.");
  }
  if (existing.status === "ENCERRADA") {
    // Já saiu do mapa: repetir o pedido criaria uma segunda mutação que o
    // servidor recusaria, e a recusa ficaria travando a fila.
    return existing;
  }
  if (existing.versao <= 0) {
    throw new Error(
      "Este desenho ainda não subiu para o servidor. Sincronize antes de encerrá-lo.",
    );
  }

  const agora = new Date().toISOString();
  const next: ObraGeometriaLocalRecord = {
    ...existing,
    status: "ENCERRADA",
    validoAte: agora,
    syncStatus: "PENDING_SYNC",
    updatedAt: agora,
  };

  await commitLocalMutation({
    ...identity,
    obraId: existing.obraId,
    entityType: "GEOMETRIA_OBRA",
    entityId: existing.id,
    entityName: existing.categoria,
    operation: "TRANSITION",
    transportOperation: "ENCERRAR_GEOMETRIA_OBRA",
    baseVersion: existing.versao,
    occurredAt: agora,
    previousSnapshot: transportSnapshot(existing),
    nextSnapshot: { ...transportSnapshot(next), motivo: razao },
    principalSnapshot: { ...next },
    // Nulo declara a ausência: quando a geometria só existia na tela, a
    // pré-condição é justamente que o dispositivo não a tinha. Mandar o
    // registro reconstruído aqui faria a pré-condição falhar contra um store
    // vazio e o encerramento morreria com "a entidade local mudou".
    expectedPrincipalSnapshot: local ? { ...local } : null,
    eventType: "GEOMETRIA_ENCERRADA",
    colaboradorId: identity.userId,
    relatedEntities: [{ tipo: "OBRA", id: existing.obraId }],
    write: () => [{ store: "obra_geometrias", value: next, principal: true }],
  });

  return next;
}
