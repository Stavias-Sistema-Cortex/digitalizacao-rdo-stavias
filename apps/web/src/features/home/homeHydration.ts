import {
  obraRecordFromPayload,
  toNumberOrNull,
} from "../../lib/db/homeRecordMappers";
import {
  mergeObrasLocais,
} from "../../lib/db/obraLocalRepository";
import {
  putPrevisaoSnapshots,
} from "../../lib/db/previsaoSnapshotRepository";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "../../lib/sync/syncSession";
import { getSession, isAlfa } from "../auth/authSession";
import type {
  ObraLocalRecord,
  PrevisaoSnapshotRecord,
} from "../../lib/db/db.types";
import {
  buscarHistoricoPrevisao,
  buscarObrasArquivadas,
  buscarObrasRelacionadas,
  type ObraRelacionadaApi,
  type PrevisaoHistoricoApi,
} from "./homeApi";

function textOrNull(value: string | null): string | null {
  return value && value.trim() ? value.trim() : null;
}

export function obraRecordFromApi(
  api: ObraRelacionadaApi,
  nowIso: string,
): ObraLocalRecord {
  return {
    id: api.id,
    codigoContrato: textOrNull(api.codigoContrato) ?? "",
    nome: textOrNull(api.nome) ?? api.id,
    cliente: textOrNull(api.cliente),
    cidade: textOrNull(api.cidade),
    uf: textOrNull(api.uf),
    rodovia: textOrNull(api.rodovia),
    status: textOrNull(api.status) ?? "ATIVA",
    observacoes: textOrNull(api.observacoes),
    latitude: toNumberOrNull(api.latitude),
    longitude: toNumberOrNull(api.longitude),
    valorContratual: toNumberOrNull(api.valorContratual),
    arquivadoEm: null,
    versaoEntidade: api.versaoLinha ?? null,
    updatedAt: textOrNull(api.atualizadoEm) ?? nowIso,
  };
}

export function snapshotRecordFromApi(
  api: PrevisaoHistoricoApi,
  nowIso: string,
): PrevisaoSnapshotRecord | null {
  const dataReferencia = textOrNull(api.dataReferencia);
  const obraId = textOrNull(api.obra?.id ?? null);

  if (!api.id || !obraId || !dataReferencia) {
    return null;
  }

  return {
    id: api.id,
    obraId,
    dataReferencia,
    statusExecucao:
      textOrNull(api.statusExecucao) ?? "CALCULADO",
    producaoPlanejada: toNumberOrNull(api.producaoPlanejada),
    producaoRealizada: toNumberOrNull(api.producaoRealizada),
    producaoApontada: toNumberOrNull(api.producaoApontada),
    // The current financial product is revenue-only. Keep the legacy local
    // schema fields empty so an old response cannot reintroduce cost data into
    // the active Home cache.
    custoRealizado: null,
    custoPrevistoFinal: null,
    receitaPrevistaFinal: toNumberOrNull(
      api.receitaPrevistaFinal,
    ),
    updatedAt: nowIso,
  };
}

export async function hydrateObrasRelacionadas(): Promise<number> {
  const guard = captureOnlineSyncSession();
  const obras = await buscarObrasRelacionadas();
  assertSyncSession(guard);
  const nowIso = new Date().toISOString();

  await mergeObrasLocais(
    obras.map((obra) => obraRecordFromApi(obra, nowIso)),
    guard,
  );

  assertSyncSession(guard);
  return obras.length;
}

export async function hydrateObrasArquivadas(): Promise<number> {
  const guard = captureAlfaSyncSession();
  const obras = await buscarObrasArquivadas();
  assertAlfaSyncSession(guard);
  const nowIso = new Date().toISOString();

  const records = obras
    .map((obra) => obraRecordFromPayload({ ...obra }, nowIso))
    .filter((record): record is ObraLocalRecord => record !== null);

  assertAlfaSyncSession(guard);
  await mergeObrasLocais(records, guard);

  assertAlfaSyncSession(guard);
  return records.length;
}

function captureAlfaSyncSession(): SyncSessionGuard {
  if (!isAlfa(getSession())) {
    throw new Error(
      "A Lixeira de obras está disponível apenas para perfis Alfa.",
    );
  }
  return captureOnlineSyncSession();
}

function assertAlfaSyncSession(guard: SyncSessionGuard): void {
  assertSyncSession(guard);
  if (!isAlfa(getSession())) {
    throw new Error(
      "A Lixeira de obras está disponível apenas para perfis Alfa.",
    );
  }
}

export async function hydrateHistoricoObra(
  obraId: string,
): Promise<number> {
  const guard = captureOnlineSyncSession();
  const historico = await buscarHistoricoPrevisao(obraId);
  assertSyncSession(guard);
  const nowIso = new Date().toISOString();

  const records = historico
    .map((item) => snapshotRecordFromApi(item, nowIso))
    .filter(
      (record): record is PrevisaoSnapshotRecord => record !== null,
    );

  assertSyncSession(guard);
  await putPrevisaoSnapshots(records, guard);

  assertSyncSession(guard);
  return records.length;
}
