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
    relatedEntities: [
      { tipo: "OBRA", id: input.obraId },
      { tipo: input.objetoTipo, id: input.objetoId },
    ],
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
  objetoId: string;
  pontos: readonly PontoGeografico[];
  propriedades?: Record<string, unknown>;
}): Promise<ObraGeometriaLocalRecord> {
  if (input.pontos.length < 2) {
    throw new Error("Um trecho exige ao menos o ponto inicial e o final.");
  }
  return enfileirarNovaGeometria(
    {
      obraId: input.obraId,
      categoria: "TRECHO",
      objetoTipo: "TRECHO",
      objetoId: input.objetoId,
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

/** Encerra a vigência de uma geometria, preservando o histórico. */
export async function encerrarGeometria(
  featureId: string,
  motivo: string,
): Promise<ObraGeometriaLocalRecord> {
  const existing = await lerGeometriaLocal(featureId);
  if (!existing) {
    throw new Error("Geometria não encontrada neste dispositivo.");
  }
  if (existing.syncStatus !== "SYNCED") {
    throw new Error(
      "A geometria ainda não foi confirmada pelo servidor. Aguarde a sincronização antes de encerrá-la.",
    );
  }
  const razao = motivo.trim();
  if (!razao) {
    throw new Error("Motivo do encerramento obrigatório.");
  }

  const identity = await geometriaMutationIdentity();
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
    expectedPrincipalSnapshot: { ...existing },
    eventType: "GEOMETRIA_ENCERRADA",
    colaboradorId: identity.userId,
    relatedEntities: [{ tipo: "OBRA", id: existing.obraId }],
    write: () => [{ store: "obra_geometrias", value: next, principal: true }],
  });

  return next;
}
