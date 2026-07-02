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
  StaviaSnapshotProgramacao,
  StaviaSnapshotRdo,
  StaviaSnapshotServicoExecutado,
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
      numero: asString(item.numero),
      kmInicial: asString(item.kmInicial),
      kmFinal: asString(item.kmFinal),
      pista: asString(item.pista),
      faixa: asString(item.faixa),
      ordemServico: asString(item.ordemServico),
      comprimentoM: asNumberOrString(item.comprimentoM),
      larguraM: asNumberOrString(item.larguraM),
      areaM2: asNumberOrString(item.areaM2),
      volumeM3: asNumberOrString(item.volumeM3),
      atividadeObservacoes: asString(
        item.atividadeObservacoes,
      ),
    };
  });
}

function mapAlocacoes(
  value: unknown,
): StaviaSnapshotAlocacao[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      colaboradorId: asString(item.colaboradorId),
      nomeColaborador: asString(item.nomeColaborador),
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

function mapServicosExecutados(
  value: unknown,
): StaviaSnapshotServicoExecutado[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      servicoNome: asString(item.servicoNome),
      quantidadeExecutada: asNumberOrString(
        item.quantidadeExecutada,
      ),
      unidade: asString(item.unidade),
      trechoInicial: asString(item.trechoInicial),
      trechoFinal: asString(item.trechoFinal),
      localizacao: asString(item.localizacao),
      turno: asString(item.turno),
      statusValidacao: asString(item.statusValidacao),
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
    cliente: asString(payload.cliente),
    cidade: asString(payload.cidade),
    contrato: asString(payload.contrato),
    rodovia: asString(payload.rodovia),
    uf: asString(payload.uf),
    kmInicialProgramado: asString(payload.kmInicialProgramado),
    kmFinalProgramado: asString(payload.kmFinalProgramado),
    kmInicialInterditado: asString(payload.kmInicialInterditado),
    kmFinalInterditado: asString(payload.kmFinalInterditado),
    turno: asString(payload.turno),
    horaInicio: asString(payload.horaInicio),
    horaFim: asString(payload.horaFim),
    condicaoManha: asString(payload.condicaoManha),
    condicaoTarde: asString(payload.condicaoTarde),
    condicaoNoite: asString(payload.condicaoNoite),
    pluviometriaMm: asNumberOrString(payload.pluviometriaMm),
    status: record.statusRdo,
    observacoes: asString(payload.observacoes),
    preenchidoPor: asString(payload.preenchidoPor),
    apontadorRdo: asString(payload.apontadorRdo),
    encarregadoObra: asString(payload.encarregadoObra),
    fiscalizacaoCampo: asString(payload.fiscalizacaoCampo),
    updatedAt: record.updatedAt,
    servicosExecutados: mapServicosExecutados(
      payload.servicosExecutados,
    ),
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

function firstNonEmpty(values: Array<unknown>): string | null {
  for (const value of values) {
    const textValue = asString(value);
    if (textValue) {
      return textValue;
    }
  }

  return null;
}

function sumNumberOrString(
  values: Array<number | string | null>,
): number | null {
  let total = 0;
  let found = false;

  for (const value of values) {
    if (value === null || value === "") {
      continue;
    }

    const parsed =
      typeof value === "number"
        ? value
        : Number(String(value).replace(",", "."));

    if (Number.isNaN(parsed)) {
      continue;
    }

    total += parsed;
    found = true;
  }

  return found ? Number(total.toFixed(3)) : null;
}

function localRdoToProgramacao(
  rdo: StaviaSnapshotRdo,
): StaviaSnapshotProgramacao | null {
  const hasProgramacaoData =
    Boolean(rdo.programacaoId) ||
    Boolean(rdo.kmInicialProgramado) ||
    Boolean(rdo.kmFinalProgramado) ||
    rdo.servicosExecutados.length > 0 ||
    rdo.controlesGeometricos.length > 0 ||
    rdo.materiais.length > 0;

  if (!hasProgramacaoData) {
    return null;
  }

  const firstControl = rdo.controlesGeometricos[0];

  return {
    id: rdo.programacaoId ?? `LOCAL-PROGRAMACAO:${rdo.id}`,
    obraId: rdo.obraId,
    rdoId: rdo.id,
    dataProgramacao: rdo.dataRdo,
    equipe: null,
    fechamento: null,
    encarregado: rdo.encarregadoObra,
    encarregadoColaboradorId: null,
    engenheiro: null,
    cliente: rdo.cliente,
    servico: firstNonEmpty(
      rdo.servicosExecutados.map((item) => item.servicoNome),
    ),
    tipoServico: null,
    cidade: rdo.cidade,
    uf: rdo.uf,
    rodovia: rdo.rodovia,
    sentido: firstControl?.pista ?? null,
    periodo: rdo.turno,
    faixa: firstControl?.faixa ?? null,
    kmInicial:
      rdo.kmInicialProgramado ?? firstControl?.kmInicial ?? null,
    kmFinal: rdo.kmFinalProgramado ?? firstControl?.kmFinal ?? null,
    extensaoM: sumNumberOrString(
      rdo.controlesGeometricos.map((item) => item.comprimentoM),
    ),
    larguraM: firstControl?.larguraM ?? null,
    espessuraCm: null,
    areaM2: sumNumberOrString(
      rdo.controlesGeometricos.map((item) => item.areaM2),
    ),
    volumeM3: sumNumberOrString(
      rdo.controlesGeometricos.map((item) => item.volumeM3),
    ),
    toneladaMassa: firstNonEmpty(
      rdo.materiais.map((item) => item.quantidadePrevista),
    ),
    tipoCap: firstNonEmpty(
      rdo.materiais.map((item) => item.materialNome),
    ),
    teorCapProjeto: null,
    cap: firstNonEmpty(
      rdo.materiais.map((item) => item.quantidadeAplicada),
    ),
    status: rdo.status,
    fonteCriacao: "RDO_LOCAL_OFFLINE",
    fonteArquivo: null,
    linhaOrigem: null,
    observacoes: rdo.observacoes,
    updatedAt: rdo.updatedAt,
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
      programacoes: [],
      pdocs: [],
    };

  const storedProgramacoes = base.programacoes ?? [];
  const localProgramacoes = localRdos
    .map(localRdoToProgramacao)
    .filter(
      (
        item,
      ): item is StaviaSnapshotProgramacao => item !== null,
    );

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
    programacoes: Array.from(
      new Map(
        [...storedProgramacoes, ...localProgramacoes].map(
          (programacao) => [programacao.id, programacao],
        ),
      ).values(),
    ).sort((left, right) =>
      (right.dataProgramacao ?? "").localeCompare(
        left.dataProgramacao ?? "",
      ),
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
    programacoes: snapshot.programacoes ?? [],
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
