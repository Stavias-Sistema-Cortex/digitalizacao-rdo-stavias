import { getCortexDb } from "../../../lib/db/cortexDb";
import type { ObraGeometriaLocalRecord } from "../../../lib/db/db.types";
import { requireDataScope } from "../../auth/authSession";
import type { ObraMapFeature } from "./mapGeometry";

export interface GeoCacheEntry<T> {
  payload: T;
  fetchedAt: string;
}

/**
 * Camadas geoespaciais da obra no dispositivo.
 *
 * O mesmo store guarda o que o servidor confirmou e o que ainda está na fila de
 * saída, particionado pelo sujeito autenticado. Nada é pré-carregado: só entra
 * aquilo que a pessoa realmente abriu ou desenhou.
 */
export async function listarGeometriasLocais(
  obraId: string,
): Promise<ObraGeometriaLocalRecord[]> {
  const { ownerId } = requireDataScope();
  const database = await getCortexDb();
  const registros = await database.getAllFromIndex(
    "obra_geometrias",
    "by-owner-worksite",
    [ownerId, obraId],
  );
  return registros.filter((registro) => registro.status === "ATIVA");
}

export async function lerGeometriaLocal(
  featureId: string,
): Promise<ObraGeometriaLocalRecord | null> {
  const { ownerId } = requireDataScope();
  const database = await getCortexDb();
  const registro = await database.get("obra_geometrias", featureId);
  return registro && registro.ownerId === ownerId ? registro : null;
}

/**
 * Reconcilia o que veio do servidor com o que existe no dispositivo.
 *
 * Registros ainda pendentes de sincronização são preservados: uma resposta do
 * servidor não pode apagar um trecho desenhado que ainda não subiu. Registros
 * antes confirmados que sumiram da resposta são removidos, porque foram
 * encerrados na origem.
 */
export async function reconciliarGeometriasDoServidor(
  obraId: string,
  features: readonly ObraMapFeature[],
  fetchedAt: string = new Date().toISOString(),
): Promise<void> {
  const { ownerId } = requireDataScope();
  const database = await getCortexDb();
  const transaction = database.transaction("obra_geometrias", "readwrite");
  const store = transaction.objectStore("obra_geometrias");
  const existentes = await store
    .index("by-owner-worksite")
    .getAll([ownerId, obraId]);
  const confirmados = new Set(features.map((feature) => feature.id));

  for (const registro of existentes) {
    if (registro.syncStatus === "SYNCED" && !confirmados.has(registro.id)) {
      await store.delete(registro.id);
    }
  }

  for (const feature of features) {
    const local = existentes.find((registro) => registro.id === feature.id);
    if (local && local.syncStatus !== "SYNCED") {
      continue;
    }
    await store.put({
      id: feature.id,
      ownerId,
      obraId,
      categoria: feature.categoria,
      objetoTipo: feature.objetoTipo,
      objetoId: feature.objetoId,
      geometry: feature.geometry,
      properties: feature.properties,
      fonte: feature.fonte,
      status: "ATIVA",
      validoDesde: feature.validoDesde,
      validoAte: feature.validoAte,
      versao: feature.versao,
      syncStatus: "SYNCED",
      fetchedAt,
      updatedAt: fetchedAt,
    });
  }

  await transaction.done;
}

/** Converte o registro local de volta ao contrato de leitura do mapa. */
export function featureDoRegistro(
  registro: ObraGeometriaLocalRecord,
): ObraMapFeature {
  return {
    id: registro.id,
    categoria: registro.categoria,
    objetoTipo: registro.objetoTipo,
    objetoId: registro.objetoId,
    geometry: registro.geometry as ObraMapFeature["geometry"],
    properties: {
      ...registro.properties,
      sincronizacao: registro.syncStatus,
    },
    fonte: registro.fonte,
    versao: registro.versao,
    validoDesde: registro.validoDesde,
    validoAte: registro.validoAte,
  };
}

export async function lerTrechoEmCache<T>(
  obraId: string,
): Promise<GeoCacheEntry<T> | null> {
  const { ownerId } = requireDataScope();
  const database = await getCortexDb();
  const record = await database.get("obra_trechos", [ownerId, obraId]);
  if (!record || record.ownerId !== ownerId) {
    return null;
  }
  return { payload: record.payload as T, fetchedAt: record.fetchedAt };
}

export async function gravarTrechoEmCache(
  obraId: string,
  payload: unknown,
  fetchedAt: string = new Date().toISOString(),
): Promise<void> {
  const { ownerId } = requireDataScope();
  const database = await getCortexDb();
  await database.put("obra_trechos", {
    key: [ownerId, obraId],
    ownerId,
    obraId,
    fetchedAt,
    payload,
  });
}
