import {
  obraRecordFromPayload,
  toNumberOrNull,
} from "../../lib/db/homeRecordMappers";
import {
  mergeObraLocal,
} from "../../lib/db/obraLocalRepository";
import {
  putPrevisaoSnapshot,
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

  if (!api.id || !api.obraId || !dataReferencia) {
    return null;
  }

  return {
    id: api.id,
    obraId: api.obraId,
    dataReferencia,
    statusExecucao:
      textOrNull(api.statusExecucao) ?? "CALCULADO",
    producaoPlanejada: toNumberOrNull(api.producaoPlanejada),
    producaoRealizada: toNumberOrNull(api.producaoRealizada),
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

  for (const obra of obras) {
    assertSyncSession(guard);
    await mergeObraLocal(
      obraRecordFromApi(obra, nowIso),
      guard,
    );
  }

  assertSyncSession(guard);
  return obras.length;
}

export async function hydrateObrasArquivadas(): Promise<number> {
  const guard = captureAlfaSyncSession();
  const obras = await buscarObrasArquivadas();
  assertAlfaSyncSession(guard);
  const nowIso = new Date().toISOString();
  let saved = 0;

  for (const obra of obras) {
    assertAlfaSyncSession(guard);
    const record = obraRecordFromPayload({ ...obra }, nowIso);
    if (!record) continue;
    await mergeObraLocal(record, guard);
    saved += 1;
  }

  assertAlfaSyncSession(guard);
  return saved;
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

  let saved = 0;

  for (const item of historico) {
    assertSyncSession(guard);
    const record = snapshotRecordFromApi(item, nowIso);

    if (record) {
      await putPrevisaoSnapshot(record, guard);
      saved += 1;
    }
  }

  assertSyncSession(guard);
  return saved;
}
