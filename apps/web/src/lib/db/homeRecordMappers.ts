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

export function obraRecordFromPayload(
  payload: Record<string, unknown>,
  fallbackNowIso: string,
): ObraLocalRecord | null {
  const id = toTextOrNull(payload.obraId);
  const nome = toTextOrNull(payload.nome);

  if (!id || !nome) {
    return null;
  }

  return {
    id,
    codigoContrato: toTextOrNull(payload.codigoContrato) ?? "",
    nome,
    cliente: toTextOrNull(payload.cliente),
    cidade: toTextOrNull(payload.cidade),
    uf: toTextOrNull(payload.uf),
    rodovia: toTextOrNull(payload.rodovia),
    status: toTextOrNull(payload.status) ?? "ATIVA",
    observacoes: toTextOrNull(payload.observacoes),
    latitude: toNumberOrNull(payload.latitude),
    longitude: toNumberOrNull(payload.longitude),
    valorContratual: toNumberOrNull(payload.valorContratual),
    updatedAt: toTextOrNull(payload.atualizadoEm) ?? fallbackNowIso,
  };
}

export function mergeObraRecords(
  existing: ObraLocalRecord | undefined,
  incoming: ObraLocalRecord,
): ObraLocalRecord {
  if (!existing) {
    return incoming;
  }

  return {
    ...incoming,
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
