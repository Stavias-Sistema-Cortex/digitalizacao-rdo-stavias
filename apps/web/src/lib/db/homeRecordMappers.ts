import type {
  ObraLocalRecord,
  PrevisaoSnapshotRecord,
} from "./db.types";

export function toNumberOrNull(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }

  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value.replace(",", "."));
    return Number.isFinite(parsed) ? parsed : null;
  }

  return null;
}

function toTextOrNull(value: unknown): string | null {
  return typeof value === "string" && value.trim()
    ? value.trim()
    : null;
}

function toSafeIntegerOrNull(value: unknown): number | null {
  return typeof value === "number" &&
      Number.isSafeInteger(value) &&
      value >= 0
    ? value
    : null;
}

function hasOwn(
  value: Record<string, unknown> | ObraLocalRecord,
  property: string,
): boolean {
  return Object.prototype.hasOwnProperty.call(value, property);
}

export function obraRecordFromPayload(
  payload: Record<string, unknown>,
  fallbackNowIso: string,
): ObraLocalRecord | null {
  const id = toTextOrNull(payload.obraId) ?? toTextOrNull(payload.id);
  const nome = toTextOrNull(payload.nome);

  if (!id || !nome) {
    return null;
  }

  const record: ObraLocalRecord = {
    id,
    codigoContrato: toTextOrNull(payload.codigoContrato) ?? "",
    codigoInterno: toTextOrNull(payload.codigoInterno),
    nome,
    cliente: toTextOrNull(payload.cliente),
    descricao: toTextOrNull(payload.descricao),
    cidade: toTextOrNull(payload.cidade),
    uf: toTextOrNull(payload.uf),
    rodovia: toTextOrNull(payload.rodovia),
    fonteArquivo: toTextOrNull(payload.fonteArquivo),
    status: toTextOrNull(payload.status) ?? "ATIVA",
    observacoes: toTextOrNull(payload.observacoes),
    latitude: toNumberOrNull(payload.latitude),
    longitude: toNumberOrNull(payload.longitude),
    valorContratual: toNumberOrNull(payload.valorContratual),
    updatedAt:
      toTextOrNull(payload.atualizadoEm) ??
      toTextOrNull(payload.updatedAt) ??
      fallbackNowIso,
  };
  const hasLifecycle =
    hasOwn(payload, "versaoEntidade") ||
    hasOwn(payload, "versaoLinha") ||
    hasOwn(payload, "arquivadoEm");
  if (hasLifecycle) {
    record.versaoEntidade =
      toSafeIntegerOrNull(payload.versaoEntidade) ??
      toSafeIntegerOrNull(payload.versaoLinha);
    record.arquivadoEm = toTextOrNull(payload.arquivadoEm);
    record.syncStatus = "SYNCED";
    record.ultimoErro = null;
  }
  return record;
}

export function mergeObraRecords(
  existing: ObraLocalRecord | undefined,
  incoming: ObraLocalRecord,
): ObraLocalRecord {
  if (!existing) {
    return incoming;
  }
  if (existing.syncStatus !== undefined && existing.syncStatus !== "SYNCED") {
    return existing;
  }

  return {
    ...incoming,
    versaoEntidade:
      incoming.versaoEntidade ?? existing.versaoEntidade ?? null,
    arquivadoEm: hasOwn(incoming, "arquivadoEm")
      ? incoming.arquivadoEm ?? null
      : existing.arquivadoEm ?? null,
    syncStatus: hasOwn(incoming, "syncStatus")
      ? incoming.syncStatus
      : existing.syncStatus,
    ultimoErro: hasOwn(incoming, "ultimoErro")
      ? incoming.ultimoErro
      : existing.ultimoErro,
    valorContratual:
      incoming.valorContratual ?? existing.valorContratual,
  };
}

export function snapshotRecordFromPayload(
  payload: Record<string, unknown>,
  fallbackNowIso: string,
): PrevisaoSnapshotRecord | null {
  const id = toTextOrNull(payload.snapshotId);
  const obraId = toTextOrNull(payload.obraId);
  const dataReferencia = toTextOrNull(payload.dataReferencia);

  if (!id || !obraId || !dataReferencia) {
    return null;
  }

  return {
    id,
    obraId,
    dataReferencia,
    statusExecucao:
      toTextOrNull(payload.statusExecucao) ?? "CALCULADO",
    producaoPlanejada: toNumberOrNull(payload.producaoPlanejada),
    producaoRealizada: toNumberOrNull(payload.producaoRealizada),
    custoRealizado: toNumberOrNull(payload.custoRealizado),
    custoPrevistoFinal: toNumberOrNull(payload.custoPrevistoFinal),
    receitaPrevistaFinal: toNumberOrNull(payload.receitaPrevistaFinal),
    updatedAt: fallbackNowIso,
  };
}
