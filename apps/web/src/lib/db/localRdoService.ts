import type {
  EquipamentoDraft,
  MaoObraDraft,
  RdoDraft,
} from "../../features/rdos/rdo.types";
import { getCortexDb } from "./cortexDb";
import type {
  LocalRdoRecord,
  OutboxMutationRecord,
} from "./db.types";

export interface SaveRdoDraftResult {
  rdo: LocalRdoRecord;
  mutation: OutboxMutationRecord;
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

function nullIfEmpty(value: string): string | null {
  return value.trim() === "" ? null : value;
}

function buildMaoObraPayload(item: MaoObraDraft) {
  const base = removeLocalId(item);

  return {
    ...base,
    colaboradorId: nullIfEmpty(base.colaboradorId),
  };
}

function buildEquipamentoPayload(
  item: EquipamentoDraft,
) {
  const base = removeLocalId(item);

  return {
    ...base,
    assetId: nullIfEmpty(base.assetId),
  };
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

function buildRdoPayload(
  draft: RdoDraft,
): Record<string, unknown> {
  return {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId: draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
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
    maoObra: draft.maoObra.map(
      buildMaoObraPayload,
    ),
    equipamentos: draft.equipamentos.map(
      buildEquipamentoPayload,
    ),
    materiais: draft.materiais
  .filter((item) => !isMaterialEmpty(item))
  .map(removeLocalId),

  controlesGeometricos:
      draft.controlesGeometricos.map(
        removeLocalId,
      ),
  };
}

function validateDraft(draft: RdoDraft): void {
  if (!draft.id.trim()) {
    throw new Error(
      "O RDO precisa ter um ID local.",
    );
  }

  if (!draft.obraId.trim()) {
    throw new Error("obraId é obrigatório.");
  }

  if (!draft.numeroRdo.trim()) {
    throw new Error(
      "numeroRdo é obrigatório.",
    );
  }

  if (!draft.dataRdo.trim()) {
    throw new Error("dataRdo é obrigatório.");
  }
}

export async function saveNewRdoDraftAtomically(
  draft: RdoDraft,
): Promise<SaveRdoDraftResult> {
  validateDraft(draft);

  const database = await getCortexDb();
  const timestamp = nowUtc();
  const payload = buildRdoPayload(draft);

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
    payload,
    createdAt: timestamp,
    updatedAt: timestamp,
  };

  const mutation: OutboxMutationRecord = {
    clientMutationId: crypto.randomUUID(),
    entidadeTipo: "RDO",
    entidadeId: draft.id,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload,
    status: "PENDING",
    tentativas: 0,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: timestamp,
    updatedAt: timestamp,
  };

  const transaction = database.transaction(
    ["rdos", "outbox_mutations"],
    "readwrite",
  );

  const rdoStore =
    transaction.objectStore("rdos");

  const outboxStore =
    transaction.objectStore(
      "outbox_mutations",
    );

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
  ]);

  await transaction.done;

  return {
    rdo,
    mutation,
  };
}

export async function saveExistingRdoDraftAtomically(
  draft: RdoDraft,
): Promise<SaveRdoDraftResult> {
  validateDraft(draft);

  const database = await getCortexDb();
  const timestamp = nowUtc();
  const payload = buildRdoPayload(draft);

  const transaction = database.transaction(
    ["rdos", "outbox_mutations"],
    "readwrite",
  );

  const rdoStore =
    transaction.objectStore("rdos");

  const outboxStore =
    transaction.objectStore(
      "outbox_mutations",
    );

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
      payload,
      status: "PENDING",
      tentativas: 0,
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
        payload,
        status: "PENDING",
        tentativas: 0,
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
        payload,
        status: "PENDING",
        tentativas: 0,
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
    payload,
    syncStatus: "PENDING_SYNC",
    updatedAt: timestamp,
  };

  await Promise.all([
    rdoStore.put(updatedRdo),
    outboxStore.put(mutation),
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