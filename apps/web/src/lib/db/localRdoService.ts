import type {
  AlocacaoColaboradorDraft,
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  RdoAttachmentDraft,
  RdoDraft,
  ServicoExecutadoDraft,
} from "../../features/rdos/rdo.types";
import { localRecordToDraft } from "../../features/rdos/localRecordToDraft";
import { getCortexDb } from "./cortexDb";
import type {
  LocalRdoChildRecord,
  LocalRdoRecord,
  LocalSyncStatus,
  OperationalEntityRef,
  OperationalEventRecord,
  OutboxMutationRecord,
} from "./db.types";
import {
  buildOperationalEvent,
  queryOperationalEvents,
} from "./operationalEventRepository";

export interface SaveRdoDraftResult {
  rdo: LocalRdoRecord;
  mutation: OutboxMutationRecord;
}

type RdoChildStoreName =
  | "rdoMaoObra"
  | "rdoEquipamentos"
  | "rdoMateriais"
  | "rdoControlesGeometricos";

interface RdoChildStoreWriter {
  index: (name: "by-rdo-id") => {
    getAllKeys: (query: string) => Promise<string[]>;
  };
  delete: (key: string) => Promise<void>;
  put: (
    value: LocalRdoChildRecord,
  ) => Promise<string>;
}

interface RdoChildWriteTransaction {
  objectStore: (
    name: RdoChildStoreName,
  ) => RdoChildStoreWriter;
}

function nowUtc(): string {
  return new Date().toISOString();
}

function removeLocalId<T extends { localId: string }>(
  item: T,
): Omit<T, "localId"> {
  const { localId, ...payload } = item;

  void localId;

  return payload;
}

export function rdoDraftFromLocalRecord(
  rdo: LocalRdoRecord,
): RdoDraft {
  return localRecordToDraft(rdo);
}

function nullIfEmpty(value: string): string | null {
  return value.trim() === "" ? null : value;
}

function entityName(value: string | null | undefined): string | null {
  if (!value || !value.trim()) {
    return null;
  }

  return value.trim();
}

function numberFromText(value: string): number | null {
  const normalized = value.trim().replace(",", ".");

  if (!normalized) {
    return null;
  }

  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

function numberFromInput(value: string | number): number | null {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }

  return numberFromText(value);
}

function round3(value: number): number {
  return Math.round((value + Number.EPSILON) * 1000) / 1000;
}

function calculatedLengthFromKm(
  kmInicial: string,
  kmFinal: string,
): number | null {
  const start = numberFromText(kmInicial);
  const end = numberFromText(kmFinal);

  if (start === null || end === null || end < start) {
    return null;
  }

  return round3((end - start) * 1000);
}

function attachmentPayload(
  attachment: RdoAttachmentDraft,
): Record<string, unknown> {
  return {
    id: attachment.id,
    rdoId: attachment.rdoId,
    obraId: attachment.obraId,
    tipo: attachment.tipo,
    nome: attachment.nome,
    nomeOriginal: attachment.nomeOriginal,
    mimeType: attachment.mimeType,
    tamanhoOriginalBytes: attachment.tamanhoOriginalBytes,
    tamanhoComprimidoBytes: attachment.tamanhoComprimidoBytes,
    tamanhoBytes: attachment.tamanhoBytes,
    syncStatus: attachment.syncStatus,
    createdAt: attachment.createdAt,
    updatedAt: attachment.updatedAt,
    removedAt: attachment.removedAt,
    metadata: attachment.metadata,
  };
}

function buildMaoObraPayload(item: MaoObraDraft) {
  const base = removeLocalId(item);

  return {
    ...base,
    colaboradorId: nullIfEmpty(base.colaboradorId),
    horaInicio: nullIfEmpty(base.horaInicio),
    horaFim: nullIfEmpty(base.horaFim),
    observacoes: nullIfEmpty(base.observacoes),
  };
}

function buildEquipamentoPayload(
  item: EquipamentoDraft,
) {
  const base = removeLocalId(item);

  return {
    ...base,
    assetId: nullIfEmpty(base.assetId),
    horaInicio: nullIfEmpty(base.horaInicio),
    horaFim: nullIfEmpty(base.horaFim),
    observacoes: nullIfEmpty(base.observacoes),
  };
}

function buildServicoExecutadoPayload(
  item: ServicoExecutadoDraft,
) {
  const base = removeLocalId(item);

  return {
    ...base,
    itemContratualId: nullIfEmpty(
      base.itemContratualId,
    ),
    unidade: nullIfEmpty(base.unidade),
    trechoInicial: nullIfEmpty(base.trechoInicial),
    trechoFinal: nullIfEmpty(base.trechoFinal),
    localizacao: nullIfEmpty(base.localizacao),
    turno: nullIfEmpty(base.turno),
    observacoes: nullIfEmpty(base.observacoes),
  };
}

function buildAlocacaoPayload(
  item: AlocacaoColaboradorDraft,
) {
  const base = removeLocalId(item);

  return {
    ...base,
    colaboradorId: nullIfEmpty(base.colaboradorId),
    equipe: nullIfEmpty(base.equipe),
    servicoNome: nullIfEmpty(base.servicoNome),
    horaInicio: nullIfEmpty(base.horaInicio),
    horaFim: nullIfEmpty(base.horaFim),
    turno: nullIfEmpty(base.turno),
    funcao: nullIfEmpty(base.funcao),
    centroCusto: nullIfEmpty(base.centroCusto),
    fonte: nullIfEmpty(base.fonte),
    observacoes: nullIfEmpty(base.observacoes),
  };
}

function isServicoExecutadoEmpty(
  item: ServicoExecutadoDraft,
): boolean {
  return (
    item.servicoNome.trim() === "" &&
    item.quantidadeExecutada === "" &&
    item.itemContratualId.trim() === ""
  );
}

function isServicoExecutadoSyncable(
  item: ServicoExecutadoDraft,
): boolean {
  if (isServicoExecutadoEmpty(item)) {
    return false;
  }

  return item.quantidadeExecutada !== "";
}

function isAlocacaoEmpty(
  item: AlocacaoColaboradorDraft,
): boolean {
  return (
    item.colaboradorId.trim() === "" &&
    item.equipe.trim() === "" &&
    item.servicoNome.trim() === ""
  );
}

function isMaterialEmpty(
  item: RdoDraft["materiais"][number],
): boolean {
  const { localId, ...fields } = item;
  void localId;

  return Object.values(fields).every(
    (value) =>
      value === null ||
      value === undefined ||
      (typeof value === "string" &&
        value.trim() === ""),
  );
}

function isMaoObraEmpty(item: MaoObraDraft): boolean {
  return (
    item.colaboradorId.trim() === "" &&
    item.nomeColaborador.trim() === "" &&
    item.cargo.trim() === "" &&
    item.quantidade === "" &&
    item.horaInicio.trim() === "" &&
    item.horaFim.trim() === "" &&
    item.observacoes.trim() === ""
  );
}

function isEquipamentoEmpty(
  item: EquipamentoDraft,
): boolean {
  return (
    item.assetId.trim() === "" &&
    item.prefixo.trim() === "" &&
    item.descricao.trim() === "" &&
    item.tipoEquipamento.trim() === "" &&
    item.quantidade === "" &&
    item.horaInicio.trim() === "" &&
    item.horaFim.trim() === "" &&
    item.observacoes.trim() === ""
  );
}

function isControleEmpty(
  item: ControleGeometricoDraft,
): boolean {
  const { localId, ...fields } = item;
  void localId;

  return Object.values(fields).every(
    (value) =>
      value === null ||
      value === undefined ||
      (typeof value === "string" &&
        value.trim() === ""),
  );
}

function buildRdoSyncPayload(
  draft: RdoDraft,
  operationalEvents: OperationalEventRecord[] = [],
): Record<string, unknown> {
  const attachments = draft.attachments ?? [];

  return {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId: draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    cliente: nullIfEmpty(draft.cliente),
    contrato: nullIfEmpty(draft.contrato),
    rodovia: nullIfEmpty(draft.rodovia),
    cidade: nullIfEmpty(draft.cidade),
    uf: nullIfEmpty(draft.uf),
    kmInicialProgramado: nullIfEmpty(
      draft.kmInicialProgramado,
    ),
    kmFinalProgramado: nullIfEmpty(
      draft.kmFinalProgramado,
    ),
    kmInicialInterditado: nullIfEmpty(
      draft.kmInicialInterditado,
    ),
    kmFinalInterditado: nullIfEmpty(
      draft.kmFinalInterditado,
    ),
    turno: draft.turno,
    horaInicio: draft.horaInicio || null,
    horaFim: draft.horaFim || null,
    condicaoManha: draft.condicaoManha || null,
    condicaoTarde: draft.condicaoTarde || null,
    condicaoNoite: draft.condicaoNoite || null,
    pluviometriaMm:
      draft.pluviometriaMm === ""
        ? null
        : draft.pluviometriaMm,
    observacoes: draft.observacoes,
    preenchidoPor: nullIfEmpty(draft.preenchidoPor),
    apontadorRdo: nullIfEmpty(draft.apontadorRdo),
    encarregadoObra: nullIfEmpty(draft.encarregadoObra),
    fiscalizacaoCampo: nullIfEmpty(
      draft.fiscalizacaoCampo,
    ),
    servicosExecutados:
      draft.servicosExecutados
        .filter(isServicoExecutadoSyncable)
        .map(buildServicoExecutadoPayload),
    alocacoesColaboradores:
      draft.alocacoesColaboradores
        .filter((item) => !isAlocacaoEmpty(item))
        .map(buildAlocacaoPayload),
    maoObra: draft.maoObra
      .filter((item) => !isMaoObraEmpty(item))
      .map(buildMaoObraPayload),
    equipamentos: draft.equipamentos
      .filter((item) => !isEquipamentoEmpty(item))
      .map(buildEquipamentoPayload),
    materiais: draft.materiais
      .filter((item) => !isMaterialEmpty(item))
      .map(removeLocalId),

    controlesGeometricos:
      draft.controlesGeometricos
        .filter((item) => !isControleEmpty(item))
        .map(removeLocalId),
    attachments: attachments
      .filter((item) => item.removedAt === null)
      .map(attachmentPayload),
    operationalEvents: operationalEvents.map((event) => ({
      id: event.id,
      type: event.type,
      principalEntity: event.principalEntity,
      relatedEntities: event.relatedEntities,
      obraId: event.obraId,
      rdoId: event.rdoId,
      colaboradorId: event.colaboradorId,
      occurredAt: event.occurredAt,
      syncedAt: event.syncedAt,
      origin: event.origin,
      responsibleUserId: event.responsibleUserId,
      responsibleUserName: event.responsibleUserName,
      payload: event.payload,
      syncStatus: event.syncStatus,
      schemaVersion: event.schemaVersion,
    })),
  };
}

export function buildRdoSyncPayloadFromLocalRecord(
  rdo: LocalRdoRecord,
  operationalEvents: OperationalEventRecord[] = [],
): Record<string, unknown> {
  return buildRdoSyncPayload(
    rdoDraftFromLocalRecord(rdo),
    operationalEvents,
  );
}

function buildRdoLocalPayload(
  draft: RdoDraft,
): Record<string, unknown> {
  const attachments = draft.attachments ?? [];

  return {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId: draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    cliente: draft.cliente,
    contrato: draft.contrato,
    rodovia: draft.rodovia,
    cidade: draft.cidade,
    uf: draft.uf,
    kmInicialProgramado:
      draft.kmInicialProgramado,
    kmFinalProgramado:
      draft.kmFinalProgramado,
    kmInicialInterditado:
      draft.kmInicialInterditado,
    kmFinalInterditado:
      draft.kmFinalInterditado,
    turno: draft.turno,
    horaInicio: draft.horaInicio,
    horaFim: draft.horaFim,
    condicaoManha: draft.condicaoManha,
    condicaoTarde: draft.condicaoTarde,
    condicaoNoite: draft.condicaoNoite,
    pluviometriaMm: draft.pluviometriaMm,
    observacoes: draft.observacoes,
    preenchidoPor: draft.preenchidoPor,
    apontadorRdo: draft.apontadorRdo,
    encarregadoObra: draft.encarregadoObra,
    fiscalizacaoCampo: draft.fiscalizacaoCampo,
    servicosExecutados: draft.servicosExecutados,
    alocacoesColaboradores:
      draft.alocacoesColaboradores,
    maoObra: draft.maoObra,
    equipamentos: draft.equipamentos,
    materiais: draft.materiais,
    controlesGeometricos:
      draft.controlesGeometricos,
    attachments: attachments.map(attachmentPayload),
  };
}

function rdoEntity(draft: RdoDraft): OperationalEntityRef {
  return {
    tipo: "RDO",
    id: draft.id,
    nome: draft.numeroRdo ? `RDO ${draft.numeroRdo}` : "RDO local",
  };
}

function obraEntity(draft: RdoDraft): OperationalEntityRef {
  return {
    tipo: "OBRA",
    id: draft.obraId,
    nome: entityName(draft.contrato) ?? entityName(draft.cliente),
  };
}

function nonEmptyRelated(
  entities: OperationalEntityRef[],
): OperationalEntityRef[] {
  return entities.filter(
    (entity) => entity.id && entity.id.trim(),
  );
}

function buildRdoSaveOperationalEvents(
  draft: RdoDraft,
  isExisting: boolean,
  timestamp: string,
): OperationalEventRecord[] {
  const rdo = rdoEntity(draft);
  const obra = obraEntity(draft);
  const base = {
    obraId: draft.obraId,
    rdoId: draft.id,
    occurredAt: timestamp,
    origin: "OFFLINE" as const,
    syncStatus: "PENDING_SYNC" as const,
    schemaVersion: 1,
  };

  const events: OperationalEventRecord[] = [
    buildOperationalEvent({
      ...base,
      type: isExisting ? "RDO_EDITADO" : "RDO_CRIADO",
      principalEntity: rdo,
      relatedEntities: nonEmptyRelated([obra]),
      payload: {
        numeroRdo: draft.numeroRdo,
        dataRdo: draft.dataRdo,
        statusRdo: draft.syncStatus,
      },
    }),
    buildOperationalEvent({
      ...base,
      type: "RDO_SALVO_OFFLINE",
      principalEntity: rdo,
      relatedEntities: nonEmptyRelated([obra]),
      payload: {
        numeroRdo: draft.numeroRdo,
        dataRdo: draft.dataRdo,
        totalFotos: (draft.attachments ?? []).filter(
          (item) => item.removedAt === null,
        ).length,
      },
    }),
    buildOperationalEvent({
      ...base,
      type: "ENTIDADE_RELACIONADA",
      principalEntity: rdo,
      relatedEntities: nonEmptyRelated([obra]),
      payload: {
        relationType: "PERTENCE_A",
        origemTipo: "RDO",
        origemId: draft.id,
        destinoTipo: "OBRA",
        destinoId: draft.obraId,
      },
    }),
  ];

  if (draft.programacaoId.trim()) {
    events.push(
      buildOperationalEvent({
        ...base,
        type: "ENTIDADE_RELACIONADA",
        principalEntity: rdo,
        relatedEntities: [
          {
            tipo: "PROGRAMACAO_OPERACIONAL",
            id: draft.programacaoId,
            nome: null,
          },
        ],
        payload: {
          relationType: "GERADO_A_PARTIR_DE",
          origemTipo: "RDO",
          origemId: draft.id,
          destinoTipo: "PROGRAMACAO_OPERACIONAL",
          destinoId: draft.programacaoId,
        },
      }),
    );
  }

  for (const item of draft.alocacoesColaboradores) {
    if (!item.colaboradorId.trim()) {
      continue;
    }

    events.push(
      buildOperationalEvent({
        ...base,
        type: "COLABORADOR_ASSOCIADO_RDO",
        principalEntity: {
          tipo: "COLABORADOR",
          id: item.colaboradorId,
          nome: entityName(item.equipe) ?? entityName(item.funcao),
        },
        relatedEntities: nonEmptyRelated([rdo, obra]),
        colaboradorId: item.colaboradorId,
        payload: {
          equipe: item.equipe,
          funcao: item.funcao,
          servicoNome: item.servicoNome,
          horaInicio: item.horaInicio,
          horaFim: item.horaFim,
          percentualDia: item.percentualDia,
          tipoAlocacao: item.tipoAlocacao,
        },
      }),
    );
  }

  for (const item of draft.maoObra) {
    const colaboradorId = item.colaboradorId.trim();
    if (!colaboradorId && !item.nomeColaborador.trim()) {
      continue;
    }

    events.push(
      buildOperationalEvent({
        ...base,
        type: "COLABORADOR_ASSOCIADO_RDO",
        principalEntity: {
          tipo: colaboradorId ? "COLABORADOR" : "RDO_MAO_OBRA",
          id: colaboradorId || item.localId,
          nome:
            entityName(item.nomeColaborador) ??
            entityName(item.cargo),
        },
        relatedEntities: nonEmptyRelated([rdo, obra]),
        colaboradorId: colaboradorId || null,
        payload: {
          nomeColaborador: item.nomeColaborador,
          cargo: item.cargo,
          tipoVinculo: item.tipoVinculo,
          quantidade: item.quantidade,
          localId: item.localId,
          localEntityId: `${draft.id}:maoObra:${item.localId}`,
        },
      }),
    );
  }

  for (const item of draft.equipamentos) {
    const assetId = item.assetId.trim();

    if (
      !assetId &&
      !item.prefixo.trim() &&
      !item.descricao.trim()
    ) {
      continue;
    }

    events.push(
      buildOperationalEvent({
        ...base,
        type: "EQUIPAMENTO_ASSOCIADO_RDO",
        principalEntity: {
          tipo: assetId ? "ATIVO" : "RDO_EQUIPAMENTO",
          id: assetId || item.localId,
          nome:
            entityName(item.prefixo) ??
            entityName(item.descricao) ??
            entityName(item.tipoEquipamento),
        },
        relatedEntities: nonEmptyRelated([rdo, obra]),
        payload: {
          prefixo: item.prefixo,
          descricao: item.descricao,
          tipoEquipamento: item.tipoEquipamento,
          tipoVinculo: item.tipoVinculo,
          quantidade: item.quantidade,
          localId: item.localId,
          localEntityId: `${draft.id}:equipamento:${item.localId}`,
        },
      }),
    );
  }

  for (const item of draft.controlesGeometricos) {
    if (isControleEmpty(item)) {
      continue;
    }

    const extensaoM =
      calculatedLengthFromKm(item.kmInicial, item.kmFinal) ??
      (typeof item.comprimentoM === "number"
        ? item.comprimentoM
        : null);

    events.push(
      buildOperationalEvent({
        ...base,
        type: "MEDICAO_TRECHO_ATUALIZADA",
        principalEntity: rdo,
        relatedEntities: [
          {
            tipo: "CONTROLE_GEOMETRICO",
            id: item.localId,
            nome:
              entityName(item.subtrecho) ??
              entityName(item.numero) ??
              entityName(item.ordemServico),
          },
        ],
        payload: {
          subtrecho: item.subtrecho,
          numero: item.numero,
          kmInicial: item.kmInicial,
          kmFinal: item.kmFinal,
          extensaoM,
          pista: item.pista,
          faixa: item.faixa,
          comprimentoM: item.comprimentoM,
          larguraM: item.larguraM,
          localId: item.localId,
          localEntityId: `${draft.id}:controle:${item.localId}`,
        },
      }),
    );
  }

  const hasCalculations =
    draft.controlesGeometricos.some((item) => !isControleEmpty(item)) ||
    draft.materiais.some((item) => !isMaterialEmpty(item));

  if (hasCalculations) {
    events.push(
      buildOperationalEvent({
        ...base,
        type: "CALCULO_REPROCESSADO",
        principalEntity: rdo,
        relatedEntities: nonEmptyRelated([obra]),
        payload: {
          controlesGeometricos: draft.controlesGeometricos.length,
          materiais: draft.materiais.length,
          calculosOffline: [
            "extensao_trecho",
            "sobra_material",
            "area_volume_massa",
          ],
        },
      }),
    );
  }

  const occurrenceText = [
    draft.observacoes,
    ...draft.servicosExecutados.map((item) => item.observacoes),
  ]
    .join(" ")
    .toLowerCase();
  const hasOccurrence =
    /ocorr|inciden|problema|paralis|chuva|interdi|rejeitad/.test(
      occurrenceText,
    ) ||
    draft.servicosExecutados.some(
      (item) => item.producaoRejeitada || item.retrabalho,
    );

  if (hasOccurrence) {
    events.push(
      buildOperationalEvent({
        ...base,
        type: "OCORRENCIA_REGISTRADA",
        principalEntity: rdo,
        relatedEntities: nonEmptyRelated([obra]),
        payload: {
          observacoes: draft.observacoes,
          servicosComRetrabalho:
            draft.servicosExecutados.filter(
              (item) => item.retrabalho,
            ).length,
          servicosRejeitados:
            draft.servicosExecutados.filter(
              (item) => item.producaoRejeitada,
            ).length,
        },
      }),
    );
  }

  return events;
}

function childRecordId(
  rdoId: string,
  collection: string,
  localId: string,
): string {
  return `${rdoId}:${collection}:${localId}`;
}

function buildChildRecord<TItem extends { localId: string }>(
  rdoId: string,
  collection: string,
  item: TItem,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord {
  return {
    id: childRecordId(
      rdoId,
      collection,
      item.localId,
    ),
    rdoId,
    localId: item.localId,
    syncStatus,
    payload: removeLocalId(item),
    createdAt: timestamp,
    updatedAt: timestamp,
  };
}

function buildMaoObraRecords(
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord[] {
  return draft.maoObra.map((item: MaoObraDraft) =>
    buildChildRecord(
      draft.id,
      "maoObra",
      item,
      syncStatus,
      timestamp,
    ),
  );
}

function buildEquipamentoRecords(
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord[] {
  return draft.equipamentos.map((item: EquipamentoDraft) =>
    buildChildRecord(
      draft.id,
      "equipamentos",
      item,
      syncStatus,
      timestamp,
    ),
  );
}

function buildMaterialRecords(
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord[] {
  return draft.materiais.map((item: MaterialDraft) =>
    buildChildRecord(
      draft.id,
      "materiais",
      item,
      syncStatus,
      timestamp,
    ),
  );
}

function buildControleGeometricoRecords(
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord[] {
  return draft.controlesGeometricos.map(
    (item: ControleGeometricoDraft) =>
      buildChildRecord(
        draft.id,
        "controlesGeometricos",
        item,
        syncStatus,
        timestamp,
      ),
  );
}

async function replaceStoreRecords(
  store: RdoChildStoreWriter,
  rdoId: string,
  records: LocalRdoChildRecord[],
): Promise<void> {
  const existingKeys =
    await store.index("by-rdo-id").getAllKeys(rdoId);

  await Promise.all(
    existingKeys.map((key) => store.delete(key)),
  );

  await Promise.all(
    records.map((record) => store.put(record)),
  );
}

async function replaceChildRecords(
  transaction: RdoChildWriteTransaction,
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): Promise<void> {
  await Promise.all([
    replaceStoreRecords(
      transaction.objectStore("rdoMaoObra"),
      draft.id,
      buildMaoObraRecords(
        draft,
        syncStatus,
        timestamp,
      ),
    ),
    replaceStoreRecords(
      transaction.objectStore("rdoEquipamentos"),
      draft.id,
      buildEquipamentoRecords(
        draft,
        syncStatus,
        timestamp,
      ),
    ),
    replaceStoreRecords(
      transaction.objectStore("rdoMateriais"),
      draft.id,
      buildMaterialRecords(
        draft,
        syncStatus,
        timestamp,
      ),
    ),
    replaceStoreRecords(
      transaction.objectStore(
        "rdoControlesGeometricos",
      ),
      draft.id,
      buildControleGeometricoRecords(
        draft,
        syncStatus,
        timestamp,
      ),
    ),
  ]);
}

export function validateRdoDraftForSync(draft: RdoDraft): void {
  if (!draft.id.trim()) {
    throw new Error(
      "O RDO precisa ter um ID local.",
    );
  }

  if (!draft.obraId.trim()) {
    throw new Error("A obra é obrigatória.");
  }

  if (!draft.numeroRdo.trim()) {
    throw new Error(
      "O número do RDO é obrigatório.",
    );
  }

  if (!draft.dataRdo.trim()) {
    throw new Error("A data do RDO é obrigatória.");
  }

  if (
    (draft.attachments ?? []).filter((item) => item.removedAt === null)
      .length > 5
  ) {
    throw new Error("O limite é de 5 fotos por RDO.");
  }

  validateKmRange(
    draft.kmInicialProgramado,
    draft.kmFinalProgramado,
    "trecho programado",
  );
  validateKmRange(
    draft.kmInicialInterditado,
    draft.kmFinalInterditado,
    "trecho interditado",
  );

  draft.controlesGeometricos.forEach((item, index) => {
    validateKmRange(
      item.kmInicial,
      item.kmFinal,
      `controle geométrico ${index + 1}`,
    );
  });

  draft.servicosExecutados.forEach((item, index) => {
    const quantidadeExecutada = numberFromInput(
      item.quantidadeExecutada,
    );

    if (
      quantidadeExecutada !== null &&
      quantidadeExecutada < 0
    ) {
      throw new Error(
        `A quantidade executada do serviço ${index + 1} deve ser maior ou igual a zero.`,
      );
    }
  });

  const requiresCaixa = draft.servicosExecutados.some((item) =>
    item.servicoNome.toLowerCase().includes("caixa"),
  );

  if (
    requiresCaixa &&
    !draft.controlesGeometricos.some(
      (item) => item.numero.trim() || item.subtrecho.trim(),
    )
  ) {
    throw new Error(
      "Informe a caixa no controle geométrico para serviços que exigem caixa.",
    );
  }
}

function validateKmRange(
  initialValue: string,
  finalValue: string,
  label: string,
): void {
  const initial = numberFromText(initialValue);
  const final = numberFromText(finalValue);

  if (initial === null || final === null) {
    return;
  }

  if (final < initial) {
    throw new Error(
      `O KM final do ${label} não pode ser menor que o KM inicial.`,
    );
  }
}

export async function saveNewRdoDraftAtomically(
  draft: RdoDraft,
): Promise<SaveRdoDraftResult> {
  validateRdoDraftForSync(draft);

  const database = await getCortexDb();
  const timestamp = nowUtc();
  const operationalEvents = buildRdoSaveOperationalEvents(
    draft,
    false,
    timestamp,
  );
  const pendingOperationalEvents = (
    await queryOperationalEvents({
      rdoId: draft.id,
      limit: 500,
    })
  ).filter((event) => event.syncStatus !== "SYNCED");
  const localPayload = buildRdoLocalPayload(draft);
  const syncPayload = buildRdoSyncPayload(draft, [
    ...pendingOperationalEvents,
    ...operationalEvents,
  ]);

  const rdo: LocalRdoRecord = {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId:
      draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: localPayload,
    createdAt: timestamp,
    updatedAt: timestamp,
  };

  const mutation: OutboxMutationRecord = {
    clientMutationId: crypto.randomUUID(),
    entidadeTipo: "RDO",
    entidadeId: draft.id,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: syncPayload,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: timestamp,
    updatedAt: timestamp,
  };

  const transaction = database.transaction(
    [
      "rdos",
      "outbox_mutations",
      "rdoMaoObra",
      "rdoEquipamentos",
      "rdoMateriais",
      "rdoControlesGeometricos",
      "operational_events",
    ],
    "readwrite",
  );

  const rdoStore =
    transaction.objectStore("rdos");

  const outboxStore =
    transaction.objectStore(
      "outbox_mutations",
    );
  const eventStore =
    transaction.objectStore("operational_events");

  const existingRdo =
    await rdoStore.get(draft.id);

  if (existingRdo) {
    transaction.abort();

    throw new Error(
      `Já existe um RDO local com o ID ${draft.id}.`,
    );
  }

  await Promise.all([
    rdoStore.add(rdo),
    outboxStore.add(mutation),
    ...operationalEvents.map((event) =>
      eventStore.put(event),
    ),
    replaceChildRecords(
      transaction,
      draft,
      "PENDING_SYNC",
      timestamp,
    ),
  ]);

  await transaction.done;

  return {
    rdo,
    mutation,
  };
}

export async function repairRdoCreateMutationsForSync(): Promise<number> {
  const database = await getCortexDb();
  const timestamp = nowUtc();
  const transaction = database.transaction(
    [
      "rdos",
      "outbox_mutations",
      "rdoMaoObra",
      "rdoEquipamentos",
      "rdoMateriais",
      "rdoControlesGeometricos",
      "operational_events",
    ],
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore(
      "outbox_mutations",
    );
  const rdoStore =
    transaction.objectStore("rdos");
  const candidates = [
    ...(await outboxStore
      .index("by-status")
      .getAll("PENDING")),
    ...(await outboxStore
      .index("by-status")
      .getAll("ERROR")),
  ];

  let repaired = 0;

  for (const mutation of candidates) {
    if (
      mutation.entidadeTipo !== "RDO" ||
      mutation.operacao !== "CRIAR_RDO"
    ) {
      continue;
    }

    const rdo = await rdoStore.get(
      mutation.entidadeId,
    );

    if (!rdo || rdo.syncStatus === "SYNCED") {
      continue;
    }

    const draft = rdoDraftFromLocalRecord(rdo);

    const repairedMutation: OutboxMutationRecord = {
      ...mutation,
      clientMutationId:
        mutation.status === "ERROR"
          ? crypto.randomUUID()
          : mutation.clientMutationId,
      payload: buildRdoSyncPayload(draft),
      status: "PENDING",
      tentativas:
        mutation.status === "ERROR"
          ? 0
          : mutation.tentativas,
      ultimaTentativaEm:
        mutation.status === "ERROR"
          ? null
          : mutation.ultimaTentativaEm,
      ultimoErro: null,
      conflito: null,
      updatedAt: timestamp,
    };

    if (mutation.status === "ERROR") {
      await outboxStore.delete(
        mutation.clientMutationId,
      );
      await outboxStore.add(repairedMutation);
    } else {
      await outboxStore.put(repairedMutation);
    }

    await rdoStore.put({
      ...rdo,
      syncStatus: "PENDING_SYNC",
      updatedAt: timestamp,
    });

    await replaceChildRecords(
      transaction,
      draft,
      "PENDING_SYNC",
      timestamp,
    );

    repaired += 1;
  }

  await transaction.done;

  return repaired;
}

export async function saveExistingRdoDraftAtomically(
  draft: RdoDraft,
): Promise<SaveRdoDraftResult> {
  validateRdoDraftForSync(draft);

  const database = await getCortexDb();
  const timestamp = nowUtc();
  const operationalEvents = buildRdoSaveOperationalEvents(
    draft,
    true,
    timestamp,
  );
  const pendingOperationalEvents = (
    await queryOperationalEvents({
      rdoId: draft.id,
      limit: 500,
    })
  ).filter((event) => event.syncStatus !== "SYNCED");
  const localPayload = buildRdoLocalPayload(draft);
  const syncPayload = buildRdoSyncPayload(draft, [
    ...pendingOperationalEvents,
    ...operationalEvents,
  ]);

  const transaction = database.transaction(
    [
      "rdos",
      "outbox_mutations",
      "rdoMaoObra",
      "rdoEquipamentos",
      "rdoMateriais",
      "rdoControlesGeometricos",
      "operational_events",
    ],
    "readwrite",
  );

  const rdoStore =
    transaction.objectStore("rdos");

  const outboxStore =
    transaction.objectStore(
      "outbox_mutations",
    );
  const eventStore =
    transaction.objectStore("operational_events");

  const existingRdo =
    await rdoStore.get(draft.id);

  if (!existingRdo) {
    transaction.abort();

    throw new Error(
      `O RDO local ${draft.id} não foi encontrado.`,
    );
  }

  if (existingRdo.statusRdo === "ENVIADO") {
    transaction.abort();

    throw new Error(
      "Um RDO enviado não pode mais ser editado.",
    );
  }

  if (
    existingRdo.syncStatus === "CONFLICT"
  ) {
    transaction.abort();

    throw new Error(
      "Este RDO possui um conflito pendente. Resolva o conflito antes de editar.",
    );
  }

  const entityIndex =
    outboxStore.index("by-entity-id");

  const entityMutations =
    await entityIndex.getAll(draft.id);

  const existingCreateMutation =
    entityMutations
      .filter(
        (candidate) =>
          candidate.operacao === "CRIAR_RDO" &&
          candidate.status !== "SYNCED",
      )
      .sort((left, right) =>
        right.criadaNoClienteEm.localeCompare(
          left.criadaNoClienteEm,
        ),
      )[0];

  let mutation: OutboxMutationRecord;

  /*
   * O RDO ainda não foi criado com sucesso no servidor.
   * Atualizamos a mutação CRIAR_RDO existente em vez de
   * criar outra mutação.
   */
  if (
    existingRdo.versaoEntidade === null &&
    existingCreateMutation
  ) {
    mutation = {
      ...existingCreateMutation,
      payload: syncPayload,
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: null,
      conflito: null,
      updatedAt: timestamp,
    };
  } else {
    /*
     * O CRIAR_RDO já foi sincronizado, mas a versão
     * do servidor ainda não foi armazenada localmente.
     * Não podemos enviar uma atualização sem baseVersao.
     */
    if (
      existingRdo.versaoEntidade === null
    ) {
      transaction.abort();

      throw new Error(
        "O RDO já existe no servidor, mas sua versão local não foi registrada. A sincronização precisa salvar versaoEntidade antes de permitir esta atualização.",
      );
    }

    const existingUpdateMutation =
      entityMutations
        .filter(
          (candidate) =>
            candidate.operacao ===
              "ATUALIZAR_RDO_RASCUNHO" &&
            candidate.status !== "SYNCED",
        )
        .sort((left, right) =>
          right.criadaNoClienteEm.localeCompare(
            left.criadaNoClienteEm,
          ),
        )[0];

    if (existingUpdateMutation) {
      mutation = {
        ...existingUpdateMutation,
        baseVersao:
          existingRdo.versaoEntidade,
        payload: syncPayload,
        status: "PENDING",
        tentativas: 0,
        ultimaTentativaEm: null,
        ultimoErro: null,
        conflito: null,
        updatedAt: timestamp,
      };
    } else {
      mutation = {
        clientMutationId:
          crypto.randomUUID(),
        entidadeTipo: "RDO",
        entidadeId: draft.id,
        operacao:
          "ATUALIZAR_RDO_RASCUNHO",
        baseVersao:
          existingRdo.versaoEntidade,
        payload: syncPayload,
        status: "PENDING",
        tentativas: 0,
        ultimaTentativaEm: null,
        ultimoErro: null,
        conflito: null,
        criadaNoClienteEm: timestamp,
        updatedAt: timestamp,
      };
    }
  }

  const updatedRdo: LocalRdoRecord = {
    ...existingRdo,
    obraId: draft.obraId,
    programacaoId:
      draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    payload: localPayload,
    syncStatus: "PENDING_SYNC",
    updatedAt: timestamp,
  };

  await Promise.all([
    rdoStore.put(updatedRdo),
    outboxStore.put(mutation),
    ...operationalEvents.map((event) =>
      eventStore.put(event),
    ),
    replaceChildRecords(
      transaction,
      draft,
      "PENDING_SYNC",
      timestamp,
    ),
  ]);

  await transaction.done;

  return {
    rdo: updatedRdo,
    mutation,
  };
}

export async function saveRdoDraftAtomically(
  draft: RdoDraft,
): Promise<SaveRdoDraftResult> {
  const database = await getCortexDb();

  const existingRdo = await database.get(
    "rdos",
    draft.id,
  );

  if (existingRdo) {
    return saveExistingRdoDraftAtomically(
      draft,
    );
  }

  return saveNewRdoDraftAtomically(draft);
}
