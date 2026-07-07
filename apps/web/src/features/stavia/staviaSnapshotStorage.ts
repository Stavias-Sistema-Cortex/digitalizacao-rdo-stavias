import { getCortexDb } from "../../lib/db/cortexDb";
import { listOperationalEvents } from "../../lib/db/operationalEventRepository";
import { listAllRdoAttachments } from "../../lib/db/rdoAttachmentRepository";
import { listLocalRdos } from "../../lib/db/rdoRepository";
import type {
  LocalRdoRecord,
  OperationalEventRecord,
  RdoAttachmentRecord,
} from "../../lib/db/db.types";
import type {
  StaviaSnapshot,
  StaviaSnapshotAlocacao,
  StaviaSnapshotAttachment,
  StaviaSnapshotControleGeometrico,
  StaviaSnapshotEquipamento,
  StaviaSnapshotMaoObra,
  StaviaSnapshotMaterial,
  StaviaSnapshotOperationalEvent,
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

function asBooleanOrString(value: unknown): boolean | string | null {
  if (typeof value === "boolean") {
    return value;
  }

  return asString(value);
}

function asNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }

  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value.replace(",", "."));
    return Number.isFinite(parsed) ? parsed : null;
  }

  return null;
}

function round3(value: number): number {
  return Math.round(value * 1000) / 1000;
}

function firstPresent<T>(values: Array<T | null>): T | null {
  return values.find((value): value is T => value !== null) ?? null;
}

function average(values: Array<number | null>): number | null {
  const present = values.filter((value): value is number => value !== null);

  if (present.length === 0) {
    return null;
  }

  return round3(
    present.reduce((sum, value) => sum + value, 0) / present.length,
  );
}

function mapMaoObra(
  value: unknown,
): StaviaSnapshotMaoObra[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      colaboradorId: asString(item.colaboradorId),
      nomeColaborador: asString(item.nomeColaborador),
      cargo: asString(item.cargo),
      tipoVinculo: asString(item.tipoVinculo),
      quantidade: asNumberOrString(item.quantidade),
      horaInicio: asString(item.horaInicio),
      horaFim: asString(item.horaFim),
      observacoes: asString(item.observacoes),
    };
  });
}

function mapEquipamentos(
  value: unknown,
): StaviaSnapshotEquipamento[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      assetId: asString(item.assetId),
      prefixo: asString(item.prefixo),
      descricao: asString(item.descricao),
      tipoEquipamento: asString(item.tipoEquipamento),
      tipoVinculo: asString(item.tipoVinculo),
      quantidade: asNumberOrString(item.quantidade),
      horaInicio: asString(item.horaInicio),
      horaFim: asString(item.horaFim),
      observacoes: asString(item.observacoes),
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
      notaFiscal: asString(item.notaFiscal),
      fornecedor: asString(item.fornecedor),
      observacoes: asString(item.observacoes),
    };
  });
}

function mapControles(
  value: unknown,
): StaviaSnapshotControleGeometrico[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);
    const comprimentoM = asNumber(item.comprimentoM);
    const larguraM = asNumber(item.larguraM);
    const espessuraMediaCm = firstPresent([
      asNumber(item.espessuraMediaCm),
      average([
        asNumber(item.espessura1Cm),
        asNumber(item.espessura2Cm),
        asNumber(item.espessura3Cm),
      ]),
    ]);
    const areaM2 = firstPresent([
      asNumber(item.areaM2),
      comprimentoM === null || larguraM === null
        ? null
        : round3(comprimentoM * larguraM),
    ]);
    const volumeM3 = firstPresent([
      asNumber(item.volumeM3),
      areaM2 === null || espessuraMediaCm === null
        ? null
        : round3(areaM2 * (espessuraMediaCm / 100)),
    ]);
    const densidade = asNumber(item.densidade);
    const massaTonelada = firstPresent([
      asNumber(item.massaTonelada),
      volumeM3 === null || densidade === null
        ? null
        : round3(volumeM3 * densidade),
    ]);

    return {
      subtrecho: asString(item.subtrecho),
      numero: asString(item.numero),
      estacaInicial: asString(item.estacaInicial),
      estacaFinal: asString(item.estacaFinal),
      kmInicial: asString(item.kmInicial),
      kmFinal: asString(item.kmFinal),
      pista: asString(item.pista),
      faixa: asString(item.faixa),
      ordemServico: asString(item.ordemServico),
      comprimentoM: asNumberOrString(item.comprimentoM),
      larguraM: asNumberOrString(item.larguraM),
      espessura1Cm: asNumberOrString(item.espessura1Cm),
      espessura2Cm: asNumberOrString(item.espessura2Cm),
      espessura3Cm: asNumberOrString(item.espessura3Cm),
      espessuraMediaCm:
        asNumberOrString(item.espessuraMediaCm) ?? espessuraMediaCm,
      areaM2: asNumberOrString(item.areaM2) ?? areaM2,
      volumeM3: asNumberOrString(item.volumeM3) ?? volumeM3,
      densidade: asNumberOrString(item.densidade),
      massaTonelada:
        asNumberOrString(item.massaTonelada) ?? massaTonelada,
      atividadeObservacoes: asString(
        item.atividadeObservacoes,
      ),
      observacoes: asString(item.observacoes),
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
      minutos: asNumberOrString(item.minutos),
      percentualDia: asNumberOrString(item.percentualDia),
      turno: asString(item.turno),
      funcao: asString(item.funcao),
      centroCusto: asString(item.centroCusto),
      tipoAlocacao: asString(item.tipoAlocacao),
      fonte: asString(item.fonte),
      status: asString(item.status),
      custoHora: asNumberOrString(item.custoHora),
      custoTotal: asNumberOrString(item.custoTotal),
      observacoes: asString(item.observacoes),
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
      itemContratualId: asString(item.itemContratualId),
      quantidadeExecutada: asNumberOrString(
        item.quantidadeExecutada,
      ),
      unidade: asString(item.unidade),
      trechoInicial: asString(item.trechoInicial),
      trechoFinal: asString(item.trechoFinal),
      localizacao: asString(item.localizacao),
      dataExecucao: asString(item.dataExecucao),
      turno: asString(item.turno),
      statusValidacao: asString(item.statusValidacao),
      estadoReceita: asString(item.estadoReceita),
      receitaOperacionalEstimativa: asNumberOrString(
        item.receitaOperacionalEstimativa,
      ),
      custoRealizado: asNumberOrString(item.custoRealizado),
      retrabalho: asBooleanOrString(item.retrabalho),
      producaoRejeitada: asBooleanOrString(
        item.producaoRejeitada,
      ),
      observacoes: asString(item.observacoes),
    };
  });
}

function mapAttachments(
  value: unknown,
): StaviaSnapshotAttachment[] {
  return asArray(value).map((raw) => {
    const item = asObject(raw);

    return {
      id: asString(item.id) ?? "",
      rdoId: asString(item.rdoId) ?? "",
      obraId: asString(item.obraId),
      tipo: asString(item.tipo),
      nome: asString(item.nome),
      nomeOriginal: asString(item.nomeOriginal),
      mimeType: asString(item.mimeType),
      tamanhoOriginalBytes: asNumberOrString(
        item.tamanhoOriginalBytes,
      ),
      tamanhoComprimidoBytes: asNumberOrString(
        item.tamanhoComprimidoBytes,
      ),
      tamanhoBytes: asNumberOrString(item.tamanhoBytes),
      syncStatus: asString(item.syncStatus),
      createdAt: asString(item.createdAt),
      updatedAt: asString(item.updatedAt),
      removedAt: asString(item.removedAt),
      metadata: asObject(item.metadata),
    };
  });
}

function localAttachmentToSnapshot(
  attachment: RdoAttachmentRecord,
): StaviaSnapshotAttachment {
  return {
    id: attachment.id,
    rdoId: attachment.rdoId,
    obraId: attachment.obraId,
    tipo: attachment.tipo,
    nome: attachment.nome,
    nomeOriginal: attachment.nomeOriginal,
    mimeType: attachment.mimeType,
    tamanhoOriginalBytes: attachment.tamanhoOriginalBytes,
    tamanhoComprimidoBytes:
      attachment.tamanhoComprimidoBytes,
    tamanhoBytes: attachment.tamanhoBytes,
    syncStatus: attachment.syncStatus,
    createdAt: attachment.createdAt,
    updatedAt: attachment.updatedAt,
    removedAt: attachment.removedAt,
    metadata: attachment.metadata,
  };
}

function localEventToSnapshot(
  event: OperationalEventRecord,
): StaviaSnapshotOperationalEvent {
  return {
    id: event.id,
    type: event.type,
    principalEntityType: event.principalEntity.tipo,
    principalEntityId: event.principalEntity.id,
    obraId: event.obraId,
    rdoId: event.rdoId,
    colaboradorId: event.colaboradorId,
    occurredAt: event.occurredAt,
    syncedAt: event.syncedAt,
    origin: event.origin,
    syncStatus: event.syncStatus,
    responsibleUserId: event.responsibleUserId,
    responsibleUserName: event.responsibleUserName,
    schemaVersion: event.schemaVersion,
    relatedEntities: event.relatedEntities,
    payload: event.payload,
  };
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
    diaSemana:
      asString(payload.diaSemana) ?? diaSemanaPt(record.dataRdo),
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
    fonteCriacao: asString(payload.fonteCriacao),
    estadoReceita: asString(payload.estadoReceita),
    fonteArquivo: asString(payload.fonteArquivo),
    abaOrigem: asString(payload.abaOrigem),
    linhaOrigem: asNumberOrString(payload.linhaOrigem),
    dataOriginal: asString(payload.dataOriginal),
    dataImportacao: asString(payload.dataImportacao),
    usuarioImportacao: asString(payload.usuarioImportacao),
    criadoEm: record.createdAt,
    enviadoEm: asString(payload.enviadoEm),
    aprovadoEm: asString(payload.aprovadoEm),
    versaoLinha: asNumberOrString(payload.versaoLinha),
    syncStatus: record.syncStatus,
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
    attachments: mapAttachments(payload.attachments),
  };
}

function diaSemanaPt(date: string | null): string | null {
  if (!date) {
    return null;
  }

  const weekday = new Date(`${date}T00:00:00`).getDay();

  return [
    "DOMINGO",
    "SEGUNDA-FEIRA",
    "TERÇA-FEIRA",
    "QUARTA-FEIRA",
    "QUINTA-FEIRA",
    "SEXTA-FEIRA",
    "SÁBADO",
  ][weekday] ?? null;
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
  localEvents: StaviaSnapshotOperationalEvent[],
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
      pdors: [],
      operationalEvents: [],
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

  const eventsById = new Map(
    [
      ...(base.operationalEvents ?? []),
      ...localEvents,
    ].map((event) => [event.id, event]),
  );

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
    pdors: base.pdors,
    operationalEvents: Array.from(eventsById.values()).sort(
      (left, right) =>
        (right.occurredAt ?? "").localeCompare(
          left.occurredAt ?? "",
        ),
    ),
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
    operationalEvents: snapshot.operationalEvents ?? [],
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
  const [stored, localRecords, attachments, events] = await Promise.all([
    getStoredStaviaSnapshot(),
    listLocalRdos(),
    listAllRdoAttachments(),
    listOperationalEvents(),
  ]);

  const attachmentsByRdo = new Map<string, StaviaSnapshotAttachment[]>();
  for (const attachment of attachments) {
    attachmentsByRdo
      .set(
        attachment.rdoId,
        [
          ...(attachmentsByRdo.get(attachment.rdoId) ?? []),
          localAttachmentToSnapshot(attachment),
        ],
      );
  }

  const localRdos = localRecords.map(localRdoToSnapshot).map((rdo) => ({
    ...rdo,
    attachments:
      attachmentsByRdo.get(rdo.id) ?? rdo.attachments ?? [],
  }));

  return mergeSnapshots(
    stored,
    localRdos,
    events.map(localEventToSnapshot),
  );
}
