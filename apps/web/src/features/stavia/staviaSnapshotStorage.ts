import { getCortexDb } from "../../lib/db/cortexDb";
import { listLocalRdos } from "../../lib/db/rdoRepository";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import type {
  StaviaSnapshot,
  StaviaSnapshotAlocacao,
  StaviaSnapshotControleGeometrico,
  StaviaSnapshotEquipamento,
  StaviaSnapshotMaoObra,
  StaviaSnapshotMaterial,
  StaviaSnapshotObra,
  StaviaSnapshotRdo,
} from "./stavia.types";

const SNAPSHOT_KEY = "default" as const;
const DICTIONARY_VERSION = "STAVIA-PT-BR-0.1.0";

function nowUtc(): string {
  return new Date().toISOString();
}

function asObject(value: unknown): Record<string, unknown> {
  if (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value)
  ) {
    return value as Record<string, unknown>;
  }

  return {};
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asString(value: unknown): string | null {
  return typeof value === "string" && value.trim()
    ? value.trim()
    : null;
}

function asNumberOrString(
  value: unknown,
): number | string | null {
  if (typeof value === "number") {
    return value;
  }

  return asString(value);
}

function mapMaoObra(
  value: unknown,
): StaviaSnapshotMaoObra[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      nomeColaborador: asString(item.nomeColaborador),
      cargo: asString(item.cargo),
      tipoVinculo: asString(item.tipoVinculo),
      quantidade: asNumberOrString(item.quantidade),
    };
  });
}

function mapEquipamentos(
  value: unknown,
): StaviaSnapshotEquipamento[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      prefixo: asString(item.prefixo),
      descricao: asString(item.descricao),
      tipoEquipamento: asString(item.tipoEquipamento),
      tipoVinculo: asString(item.tipoVinculo),
      quantidade: asNumberOrString(item.quantidade),
    };
  });
}

function mapMateriais(
  value: unknown,
): StaviaSnapshotMaterial[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      materialNome: asString(item.materialNome),
      unidade: asString(item.unidade),
      quantidadePrevista: asNumberOrString(
        item.quantidadePrevista,
      ),
      quantidadeUsinada: asNumberOrString(
        item.quantidadeUsinada,
      ),
      quantidadeAplicada: asNumberOrString(
        item.quantidadeAplicada,
      ),
      quantidadeSobra: asNumberOrString(
        item.quantidadeSobra,
      ),
    };
  });
}

function mapControles(
  value: unknown,
): StaviaSnapshotControleGeometrico[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      subtrecho: asString(item.subtrecho),
      kmInicial: asString(item.kmInicial),
      kmFinal: asString(item.kmFinal),
      comprimentoM: asNumberOrString(item.comprimentoM),
      larguraM: asNumberOrString(item.larguraM),
      areaM2: asNumberOrString(item.areaM2),
      volumeM3: asNumberOrString(item.volumeM3),
    };
  });
}

function mapAlocacoes(
  value: unknown,
): StaviaSnapshotAlocacao[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      equipe: asString(item.equipe),
      servicoNome: asString(item.servicoNome),
      horaInicio: asString(item.horaInicio),
      horaFim: asString(item.horaFim),
      turno: asString(item.turno),
      funcao: asString(item.funcao),
      status: asString(item.status),
    };
  });
}

function localRdoToSnapshot(
  record: LocalRdoRecord,
): StaviaSnapshotRdo {
  const payload = asObject(record.payload);

  return {
    id: record.id,
    obraId: record.obraId,
    programacaoId: record.programacaoId,
    numeroRdo: record.numeroRdo,
    dataRdo: record.dataRdo,
    cidade: asString(payload.cidade),
    contrato: asString(payload.contrato),
    rodovia: asString(payload.rodovia),
    uf: asString(payload.uf),
    turno: asString(payload.turno),
    horaInicio: asString(payload.horaInicio),
    horaFim: asString(payload.horaFim),
    status: record.statusRdo,
    observacoes: asString(payload.observacoes),
    updatedAt: record.updatedAt,
    maoObra: mapMaoObra(payload.maoObra),
    equipamentos: mapEquipamentos(payload.equipamentos),
    materiais: mapMateriais(payload.materiais),
    controlesGeometricos: mapControles(
      payload.controlesGeometricos,
    ),
    alocacoesColaboradores: mapAlocacoes(
      payload.alocacoesColaboradores,
    ),
  };
}

function localObrasFromRdos(
  rdos: StaviaSnapshotRdo[],
): StaviaSnapshotObra[] {
  const byId = new Map<string, StaviaSnapshotObra>();

  for (const rdo of rdos) {
    if (byId.has(rdo.obraId)) {
      continue;
    }

    byId.set(rdo.obraId, {
      id: rdo.obraId,
      codigoContrato: rdo.contrato,
      codigoCw: null,
      codigoInterno: null,
      nome: rdo.contrato
        ? `Obra ${rdo.contrato}`
        : "Obra local",
      cliente: null,
      cidade: rdo.cidade,
      uf: rdo.uf,
      rodovia: rdo.rodovia,
      status: null,
      updatedAt: rdo.updatedAt,
    });
  }

  return Array.from(byId.values());
}

function mergeSnapshots(
  stored: StaviaSnapshot | null,
  localRdos: StaviaSnapshotRdo[],
): StaviaSnapshot | null {
  if (!stored && localRdos.length === 0) {
    return null;
  }

  const timestamp = nowUtc();
  const base: StaviaSnapshot =
    stored ?? {
      metadata: {
        snapshotKey: SNAPSHOT_KEY,
        generatedAt: timestamp,
        databaseUpdatedAt: null,
        localSyncedAt: null,
        source: "LOCAL_RDO_INDEX",
        status: "LOCAL",
        dictionaryVersion: DICTIONARY_VERSION,
      },
      obras: [],
      rdos: [],
      pdocs: [],
    };

  const rdosById = new Map(
    base.rdos.map((rdo) => [rdo.id, rdo]),
  );
  for (const rdo of localRdos) {
    rdosById.set(rdo.id, rdo);
  }

  const obrasById = new Map(
    base.obras.map((obra) => [obra.id, obra]),
  );
  for (const obra of localObrasFromRdos(localRdos)) {
    obrasById.set(obra.id, {
      ...obra,
      ...obrasById.get(obra.id),
      cidade: obrasById.get(obra.id)?.cidade ?? obra.cidade,
      rodovia: obrasById.get(obra.id)?.rodovia ?? obra.rodovia,
      codigoContrato:
        obrasById.get(obra.id)?.codigoContrato ??
        obra.codigoContrato,
    });
  }

  return {
    metadata: {
      ...base.metadata,
      status: stored ? base.metadata.status : "LOCAL",
      dictionaryVersion:
        base.metadata.dictionaryVersion ||
        DICTIONARY_VERSION,
    },
    obras: Array.from(obrasById.values()),
    rdos: Array.from(rdosById.values()).sort((left, right) =>
      (right.dataRdo ?? "").localeCompare(left.dataRdo ?? ""),
    ),
    pdocs: base.pdocs,
  };
}

export async function saveStaviaSnapshot(
  snapshot: StaviaSnapshot,
): Promise<StaviaSnapshot> {
  const database = await getCortexDb();
  const timestamp = nowUtc();
  const normalized: StaviaSnapshot = {
    ...snapshot,
    metadata: {
      ...snapshot.metadata,
      snapshotKey: SNAPSHOT_KEY,
      localSyncedAt: timestamp,
      dictionaryVersion:
        snapshot.metadata.dictionaryVersion ||
        DICTIONARY_VERSION,
    },
  };

  await database.put("stavia_snapshots", {
    key: SNAPSHOT_KEY,
    snapshot: normalized,
    localSyncedAt: timestamp,
    updatedAt: timestamp,
  });

  return normalized;
}

export async function getStoredStaviaSnapshot(): Promise<StaviaSnapshot | null> {
  const database = await getCortexDb();
  const record = await database.get(
    "stavia_snapshots",
    SNAPSHOT_KEY,
  );

  return record?.snapshot ?? null;
}

export async function getBestAvailableStaviaSnapshot(): Promise<StaviaSnapshot | null> {
  const [stored, localRecords] = await Promise.all([
    getStoredStaviaSnapshot(),
    listLocalRdos(),
  ]);

  return mergeSnapshots(
    stored,
    localRecords.map(localRdoToSnapshot),
  );
}
