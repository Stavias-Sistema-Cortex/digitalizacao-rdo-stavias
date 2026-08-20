import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  PreferenciaDeConversaLocal,
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  MensagemLocalRecord,
  OutboxMutationRecord,
} from "../../lib/db/db.types";
import { getSession } from "../auth/authSession";
import type {
  ConversationApi,
  MessageApi,
} from "./mensagensApi";
import {
  buildQueuedConversation,
  buildQueuedMessage,
  type BuildQueuedConversationInput,
  type QueuedMessagePlan,
} from "./mensagensQueue";
import {
  buildConversationPreviews,
  type ConversationPreview,
} from "./mensagensView";
import { guardSyncTransaction } from "../../lib/sync/guardedSyncTransaction";
import { anunciarEscritaLocal } from "../../lib/sync/localMutationCoordinator";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "../../lib/sync/syncSession";
import { compararInstantesDoServidor } from "../../lib/tempo/fusoBrasilia";
import { emitMessagesChanged } from "./mensagensEvents";
import {
  camposPendentesDaPreferencia,
  pendenciasDaPreferencia,
  preferenciaComPendencias,
  type CampoPendenteDePreferencia,
} from "./mensagemPreferenciaPendente";

export {
  emitMessagesChanged,
  MESSAGES_CHANGED_EVENT,
} from "./mensagensEvents";

const DEFAULT_MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024;
const MESSAGING_ACCESS_REVOKED_REVIEW_CODE =
  "MESSAGING_ACCESS_REVOKED_REQUIRES_REVIEW";
const MESSAGING_AUTHORIZATION_SNAPSHOT_ID =
  "__cortex_messaging_authorization_snapshot__";
const MESSAGING_AUTHORIZATION_SEQUENCE_ID =
  "__cortex_messaging_authorization_sequence__";
const DEFAULT_ALLOWED_MEDIA_TYPES = new Set([
  "application/pdf",
  "application/zip",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  "image/png",
  "image/jpeg",
  "image/gif",
  "image/webp",
  "text/plain",
  "text/csv",
]);

export interface MensagemComAnexos extends MensagemLocalRecord {
  anexos: MensagemAnexoLocalRecord[];
}

export interface StoreServerMessageOptions {
  /** Ordem reservada antes da requisição que produziu esta resposta remota. */
  requestOrdinal?: number;
}

function authorizationControlRecord(id: string): boolean {
  return id === MESSAGING_AUTHORIZATION_SNAPSHOT_ID ||
    id === MESSAGING_AUTHORIZATION_SEQUENCE_ID;
}

function validMessagingOrdinal(value: number | undefined): number {
  return Number.isSafeInteger(value) && (value ?? 0) > 0 ? value! : 0;
}

/**
 * Reserva uma ordem global do escopo local antes de iniciar uma requisição.
 *
 * A transação readwrite é serializada pelo IndexedDB inclusive entre abas. A
 * ordem não usa Date.now(): duas requisições no mesmo milissegundo e um relógio
 * que ande para trás continuam tendo uma relação inequívoca.
 */
export async function reserveMessagingRequestOrdinal(): Promise<number> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    "mensagem_autorizacoes",
    "readwrite",
  );
  const store = transaction.objectStore("mensagem_autorizacoes");
  const sequence = await store.get(MESSAGING_AUTHORIZATION_SEQUENCE_ID);
  const currentSnapshot = await store.get(MESSAGING_AUTHORIZATION_SNAPSHOT_ID);
  const current = Math.max(
    validMessagingOrdinal(sequence?.ordinal),
    validMessagingOrdinal(currentSnapshot?.ordinal),
  );
  if (current >= Number.MAX_SAFE_INTEGER) {
    transaction.abort();
    throw new Error("A ordem local de autorização de mensagens se esgotou.");
  }
  const next = current + 1;
  await store.put({
    id: MESSAGING_AUTHORIZATION_SEQUENCE_ID,
    ordinal: next,
  });
  await transaction.done;
  return next;
}

export function maxMessageAttachmentBytes(): number {
  const configured = Number(
    import.meta.env.VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES,
  );
  return Number.isSafeInteger(configured) && configured > 0
    ? configured
    : DEFAULT_MAX_ATTACHMENT_BYTES;
}

export function validateMessageFiles(files: File[]): void {
  const maxBytes = maxMessageAttachmentBytes();
  for (const file of files) {
    if (file.size <= 0) {
      throw new Error(`O anexo ${file.name || "sem nome"} está vazio.`);
    }
    if (file.size > maxBytes) {
      throw new Error(
        `O anexo ${file.name || "sem nome"} excede o limite de ${formatBytes(maxBytes)}.`,
      );
    }
    const type = file.type || "application/octet-stream";
    if (!DEFAULT_ALLOWED_MEDIA_TYPES.has(type)) {
      throw new Error(`O tipo ${type} não é permitido para anexos.`);
    }
  }
}

/**
 * Cria a conversa no dispositivo e a coloca na fila de subida.
 *
 * <p>Era a única escrita de Mensagens que falava direto com o servidor, e por
 * isso a única que o campo não podia fazer: sem rede, o botão de nova conversa
 * ficava desabilitado. Agora ela segue o mesmo caminho de todo o resto —
 * escreve local, sobe depois — e a tela deixa de ter dois comportamentos
 * conforme o sinal.
 */
export async function queueConversation(
  input: Omit<BuildQueuedConversationInput, "autorId" | "autorNome">,
): Promise<ConversaLocalRecord> {
  const session = getSession();
  if (!session) {
    throw new Error("Abra a sessão protegida antes de criar conversas.");
  }
  const plan = buildQueuedConversation({
    ...input,
    autorId: session.colaboradorId,
    autorNome: session.nome,
  });
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["mensagem_conversas", "mensagem_preferencias", "outbox_mutations"],
    "readwrite",
  );
  if (plan.conversation.tipo === "DIRETA") {
    const conversations = await transaction
      .objectStore("mensagem_conversas")
      .getAll();
    const existing = conversations.find((conversation) =>
      sameActiveDirectParticipants(
        conversation,
        plan.conversation.participantes.map(({ colaboradorId }) =>
          colaboradorId
        ),
      )
    );
    if (existing) {
      const preferenceStore = transaction.objectStore(
        "mensagem_preferencias",
      );
      const preference = await preferenceStore.get(existing.id);
      if (preference?.arquivadoEm) {
        const reopenedAt = plan.conversation.criadaEm;
        await preferenceStore.put(preferenciaComPendencias({
          ...preference,
          conversaId: existing.id,
          arquivadoEm: null,
          limpoAte: reopenedAt,
          arquivadoAtualizadoEm: reopenedAt,
          limpoAtualizadoEm: reopenedAt,
        }, {
          arquivamento: true,
          limpeza: true,
        }));
      }
      await transaction.done;
      emitMessagesChanged();
      anunciarEscritaLocal();
      return existing;
    }
  }
  await transaction.objectStore("mensagem_conversas").add(plan.conversation);
  await transaction.objectStore("outbox_mutations").add(plan.mutation);
  if (plan.conversation.tipo === "DIRETA") {
    const startedAt = plan.conversation.criadaEm;
    await transaction.objectStore("mensagem_preferencias").put(
      preferenciaComPendencias({
        conversaId: plan.conversation.id,
        arquivadoEm: null,
        limpoAte: startedAt,
        arquivadoAtualizadoEm: startedAt,
        limpoAtualizadoEm: startedAt,
        pendente: true,
      }, {
        arquivamento: true,
        limpeza: true,
      }),
    );
  }
  await transaction.done;
  emitMessagesChanged();
  anunciarEscritaLocal();
  return plan.conversation;
}

function sameActiveDirectParticipants(
  conversation: ConversaLocalRecord,
  expectedParticipantIds: readonly string[],
): boolean {
  if (conversation.tipo !== "DIRETA" || conversation.status !== "ATIVA") {
    return false;
  }
  const actual = conversation.participantes
    .filter((participant) => participant.status === "ATIVO")
    .map(({ colaboradorId }) => colaboradorId)
    .sort();
  const expected = [...new Set(expectedParticipantIds)].sort();
  return actual.length === expected.length &&
    actual.every((participantId, index) => participantId === expected[index]);
}

export async function queueMessage(input: {
  conversaId: string;
  corpo: string;
  files: File[];
}): Promise<MensagemComAnexos> {
  const session = getSession();
  if (!session) {
    throw new Error("Abra a sessão protegida antes de criar mensagens locais.");
  }
  validateMessageFiles(input.files);
  const plan = buildQueuedMessage({
    ...input,
    autorId: session.colaboradorId,
    autorNome: session.nome,
  });
  const hashedPlan = await addAttachmentHashes(plan);
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["mensagens", "mensagem_anexos", "outbox_mutations"],
    "readwrite",
  );

  const criacaoDaConversa = await transaction
    .objectStore("outbox_mutations")
    .get(input.conversaId);
  const canonicalConversationId = resolvedConversationId(
    input.conversaId,
    criacaoDaConversa,
  );
  const effectivePlan = canonicalConversationId === input.conversaId
    ? hashedPlan
    : remapQueuedMessagePlan(hashedPlan, canonicalConversationId);

  await transaction.objectStore("mensagens").add(effectivePlan.message);
  for (const attachment of effectivePlan.attachments) {
    await transaction.objectStore("mensagem_anexos").add(attachment);
  }
  /*
   * Uma mensagem escrita numa conversa que ainda não subiu precisa esperar
   * por ela. Sem isso o servidor receberia a mensagem antes da conversa e a
   * recusaria por conversa inexistente — e a recusa travaria a fila inteira
   * atrás dela, que é o pior desfecho possível para quem apontou em campo.
   */
  const dependeDaConversa =
    criacaoDaConversa?.entidadeTipo === "CONVERSA" &&
    criacaoDaConversa.operacao === "CRIAR_CONVERSA" &&
    criacaoDaConversa.status !== "SYNCED";
  const messageMutation = dependeDaConversa
    ? {
      ...effectivePlan.messageMutation,
      dependsOnMutationIds: [
        ...(effectivePlan.messageMutation.dependsOnMutationIds ?? []),
        criacaoDaConversa.clientMutationId,
      ],
    }
    : effectivePlan.messageMutation;
  for (const mutation of [
    ...effectivePlan.uploadMutations,
    messageMutation,
  ]) {
    await transaction.objectStore("outbox_mutations").add(mutation);
  }
  await transaction.done;
  emitMessagesChanged();
  /*
   * A mensagem entra na fila pela escrita direta, sem passar pelo coordenador
   * canônico — e por isso era a única escrita local que não avisava o
   * agendador. Quem enviava daqui era salvo pelo `syncNow` explícito da tela;
   * qualquer outro caminho ficava esperando a janela de trinta segundos. O
   * aviso é o contrato de toda escrita local: escreveu, o sync acorda.
   */
  anunciarEscritaLocal();
  return {
    ...effectivePlan.message,
    anexos: effectivePlan.attachments,
  };
}

function resolvedConversationId(
  requestedId: string,
  creation: OutboxMutationRecord | undefined,
): string {
  if (
    creation?.entidadeTipo !== "CONVERSA" ||
    creation.operacao !== "CRIAR_CONVERSA" ||
    creation.entidadeId !== requestedId ||
    creation.status !== "SYNCED"
  ) {
    return requestedId;
  }
  const resolved = creation.resultadoServidor?.id;
  return typeof resolved === "string" && resolved.trim()
    ? resolved.trim()
    : requestedId;
}

/** Resolve o alias durável criado quando uma conversa direta já existia. */
export async function resolveLocalConversationId(
  requestedId: string,
): Promise<string> {
  const database = await getCortexDb();
  const creation = await database.get("outbox_mutations", requestedId);
  return resolvedConversationId(requestedId, creation);
}

function remapQueuedMessagePlan(
  plan: QueuedMessagePlan,
  conversationId: string,
): QueuedMessagePlan {
  const remapMutation = (
    mutation: OutboxMutationRecord,
  ): OutboxMutationRecord => ({
    ...mutation,
    payload: mutation.payload.conversaId === plan.message.conversaId
      ? { ...mutation.payload, conversaId: conversationId }
      : mutation.payload,
  });
  return {
    message: { ...plan.message, conversaId: conversationId },
    attachments: plan.attachments.map((attachment) => ({
      ...attachment,
      conversaId: conversationId,
    })),
    uploadMutations: plan.uploadMutations.map(remapMutation),
    messageMutation: remapMutation(plan.messageMutation),
  };
}

export async function listLocalConversations(): Promise<
  ConversaLocalRecord[]
> {
  const database = await getCortexDb();
  // `atualizadaEm` sem offset é cache do contrato UTC legado, não hora local.
  return (await database.getAll("mensagem_conversas")).sort(
    (left, right) =>
      compararInstantesDoServidor(
        right.atualizadaEm,
        left.atualizadaEm,
      ),
  );
}

export async function listLocalConversationPreviews(): Promise<
  Record<string, ConversationPreview>
> {
  const database = await getCortexDb();
  const [messages, attachments] = await Promise.all([
    database.getAll("mensagens"),
    database.getAll("mensagem_anexos"),
  ]);
  return buildConversationPreviews(
    messages,
    new Set(attachments.map((attachment) => attachment.mensagemId)),
  );
}

/**
 * A arrumação da caixa desta pessoa, lida do aparelho.
 *
 * <p>Sem registro, a conversa está na lista e o histórico inteiro à vista —
 * que é o estado de quem nunca arrumou nada.
 */
export async function lerPreferenciaDaConversa(
  conversaId: string,
): Promise<PreferenciaDeConversaLocal> {
  const database = await getCortexDb();
  const guardada = await database.get("mensagem_preferencias", conversaId);
  if (guardada) {
    return preferenciaComPendencias(
      guardada,
      pendenciasDaPreferencia(guardada),
    );
  }
  return {
    conversaId,
    arquivadoEm: null,
    limpoAte: null,
    arquivadoPendente: false,
    limpoPendente: false,
    pendente: false,
  };
}

export async function listarPreferenciasDeConversa(): Promise<
  Map<string, PreferenciaDeConversaLocal>
> {
  const database = await getCortexDb();
  const todas = await database.getAll("mensagem_preferencias");
  return new Map(todas.map((item) => [
    item.conversaId,
    preferenciaComPendencias(item, pendenciasDaPreferencia(item)),
  ]));
}

export async function listarPreferenciasPendentesDaConversa(
  guard?: SyncSessionGuard,
): Promise<PreferenciaDeConversaLocal[]> {
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);
  const preferences = await database.getAll("mensagem_preferencias");
  if (guard) assertSyncSession(guard);
  return preferences
    .map((preference) => preferenciaComPendencias(
      preference,
      pendenciasDaPreferencia(preference),
    ))
    .filter((preference) => preference.pendente);
}

/**
 * Confirma somente o gesto que acabou de subir.
 *
 * <p>Se a pessoa mudou de ideia enquanto a requisição estava em voo, os
 * valores já não coincidem e a preferência nova continua pendente.</p>
 */
export async function confirmarPreferenciaDaConversaSincronizada(
  expected: PreferenciaDeConversaLocal,
  guard?: SyncSessionGuard,
  fields: readonly CampoPendenteDePreferencia[] =
    camposPendentesDaPreferencia(expected),
): Promise<boolean> {
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);
  const rawTransaction = database.transaction(
    "mensagem_preferencias",
    "readwrite",
  );
  const guardedTransaction = guard
    ? guardSyncTransaction(rawTransaction, guard)
    : null;
  const transaction = guardedTransaction?.transaction ?? rawTransaction;
  const store = transaction.objectStore("mensagem_preferencias");
  const current = await store.get(expected.conversaId);
  const currentPending = current
    ? pendenciasDaPreferencia(current)
    : { arquivamento: false, limpeza: false };
  const unchanged = Boolean(current) && fields.length > 0 && fields.every(
    (field) => field === "ARQUIVAMENTO"
      ? currentPending.arquivamento &&
        current?.arquivadoEm === expected.arquivadoEm &&
        current?.arquivadoAtualizadoEm === expected.arquivadoAtualizadoEm
      : currentPending.limpeza &&
        current?.limpoAte === expected.limpoAte &&
        current?.limpoAtualizadoEm === expected.limpoAtualizadoEm,
  );
  if (current && unchanged) {
    await store.put(preferenciaComPendencias(current, {
      arquivamento: fields.includes("ARQUIVAMENTO")
        ? false
        : currentPending.arquivamento,
      limpeza: fields.includes("LIMPEZA")
        ? false
        : currentPending.limpeza,
    }));
  }
  if (guardedTransaction) {
    await guardedTransaction.complete();
  } else {
    await transaction.done;
  }
  return unchanged;
}

/**
 * Grava o gesto no aparelho antes de qualquer rede.
 *
 * <p>É o que faz "limpar" limpar de verdade: a tela lê daqui, então o efeito é
 * imediato e sobrevive ao modo avião. A ida ao servidor vem depois e, quando
 * confirma, apaga a marca de pendente.
 */
export async function gravarPreferenciaDaConversa(
  conversaId: string,
  mudanca: Partial<
    Pick<
      PreferenciaDeConversaLocal,
      "arquivadoEm" | "limpoAte" | "pendente"
    >
  >,
): Promise<PreferenciaDeConversaLocal> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["mensagem_preferencias", "outbox_mutations"],
    "readwrite",
  );
  const creation = await transaction
    .objectStore("outbox_mutations")
    .get(conversaId);
  const effectiveConversationId = resolvedConversationId(
    conversaId,
    creation,
  );
  const preferenceStore = transaction.objectStore("mensagem_preferencias");
  const atual = (await preferenceStore.get(effectiveConversationId)) ?? {
    conversaId: effectiveConversationId,
    arquivadoEm: null,
    limpoAte: null,
    arquivadoPendente: false,
    limpoPendente: false,
    pendente: false,
  };
  const gestureAt = new Date().toISOString();
  const archiveChanged = Object.prototype.hasOwnProperty.call(
    mudanca,
    "arquivadoEm",
  );
  const historyChanged = Object.prototype.hasOwnProperty.call(
    mudanca,
    "limpoAte",
  );
  const currentPending = pendenciasDaPreferencia(atual);
  let arquivamentoPendente = currentPending.arquivamento;
  let limpezaPendente = currentPending.limpeza;
  if (mudanca.pendente === true) {
    if (archiveChanged) arquivamentoPendente = true;
    if (historyChanged) limpezaPendente = true;
  } else if (mudanca.pendente === false) {
    if (archiveChanged) arquivamentoPendente = false;
    if (historyChanged) limpezaPendente = false;
    if (!archiveChanged && !historyChanged) {
      arquivamentoPendente = false;
      limpezaPendente = false;
    }
  }
  const proxima = preferenciaComPendencias({
    ...atual,
    ...mudanca,
    ...(archiveChanged ? { arquivadoAtualizadoEm: gestureAt } : {}),
    ...(historyChanged ? { limpoAtualizadoEm: gestureAt } : {}),
    conversaId: effectiveConversationId,
  }, {
    arquivamento: arquivamentoPendente,
    limpeza: limpezaPendente,
  });
  await preferenceStore.put(proxima);
  await transaction.done;
  return proxima;
}

export async function listLocalMessages(
  conversationId: string,
): Promise<MensagemComAnexos[]> {
  const database = await getCortexDb();
  const transaction = database.transaction([
    "mensagem_autorizacoes",
    "mensagem_conversas",
    "mensagem_preferencias",
    "mensagens",
    "mensagem_anexos",
    "outbox_mutations",
  ]);
  const authorizationStore = transaction.objectStore(
    "mensagem_autorizacoes",
  );
  const [authorizationSnapshot, authorization, conversation, preferencia,
    messages, attachments, outbox] = await Promise.all([
    authorizationStore.get(MESSAGING_AUTHORIZATION_SNAPSHOT_ID),
    authorizationStore.get(conversationId),
    transaction.objectStore("mensagem_conversas").get(conversationId),
    transaction.objectStore("mensagem_preferencias").get(conversationId),
    transaction.objectStore("mensagens")
      .index("by-conversation-id")
      .getAll(conversationId),
    transaction.objectStore("mensagem_anexos")
      .index("by-conversation-id")
      .getAll(conversationId),
    transaction.objectStore("outbox_mutations").getAll(),
  ]);
  await transaction.done;
  const locallyCreated = outbox.some(
    (mutation) =>
      mutation.entidadeTipo === "CONVERSA" &&
      mutation.operacao === "CRIAR_CONVERSA" &&
      resolvedConversationId(mutation.entidadeId, mutation) === conversationId,
  );
  if (authorizationSnapshot && !authorization && !locallyCreated) {
    return [];
  }
  if (!conversation) {
    const quarantined = outbox.some(
      (mutation) =>
        mutation.lastSafeCode === MESSAGING_ACCESS_REVOKED_REVIEW_CODE &&
        mutation.payload.conversaId === conversationId,
    );
    if (quarantined) {
      return [];
    }
  }
  // A cortina de quem limpou a conversa. Sem ela aqui, "limpar" só valia na
  // resposta do servidor e a tela seguia mostrando o cache do aparelho — o
  // botão parecia morto, e só arquivar e desarquivar (que descarta e rebaixa
  // o cache) fazia o histórico sumir.
  const byMessage = new Map<string, MensagemAnexoLocalRecord[]>();
  for (const attachment of attachments) {
    const current = byMessage.get(attachment.mensagemId) ?? [];
    current.push(attachment);
    byMessage.set(attachment.mensagemId, current);
  }
  return messages
    .filter(
      (message) =>
        !preferencia?.limpoAte ||
        compararInstantesDoServidor(
          message.criadaNoClienteEm,
          preferencia.limpoAte,
        ) > 0,
    )
    .sort((left, right) =>
      compararInstantesDoServidor(
        left.criadaNoClienteEm,
        right.criadaNoClienteEm,
      ),
    )
    .map((message) => ({
      ...message,
      anexos: (byMessage.get(message.id) ?? []).sort(
        (left, right) => left.ordem - right.ordem,
      ),
    }));
}

export async function searchLocalMessages(
  query: string,
): Promise<MensagemComAnexos[]> {
  const normalized = query.trim().toLocaleLowerCase("pt-BR");
  if (!normalized) {
    return [];
  }
  const database = await getCortexDb();
  const transaction = database.transaction([
    "mensagem_autorizacoes",
    "mensagem_conversas",
    "mensagem_preferencias",
    "mensagens",
    "mensagem_anexos",
    "outbox_mutations",
  ]);
  const [authorization, conversations, storedPreferences, storedMessages,
    allAttachments, outbox] = await Promise.all([
    transaction.objectStore("mensagem_autorizacoes").getAll(),
    transaction.objectStore("mensagem_conversas").getAll(),
    transaction.objectStore("mensagem_preferencias").getAll(),
    transaction.objectStore("mensagens").getAll(),
    transaction.objectStore("mensagem_anexos").getAll(),
    transaction.objectStore("outbox_mutations").getAll(),
  ]);
  await transaction.done;
  const snapshotInitialized = authorization.some(
    ({ id }) => id === MESSAGING_AUTHORIZATION_SNAPSHOT_ID,
  );
  const visibleConversationIds = new Set(
    snapshotInitialized
      ? authorization
          .filter(({ id }) => !authorizationControlRecord(id))
          .map(({ id }) => id)
      : conversations.map(({ id }) => id),
  );
  for (const mutation of outbox) {
    if (
      mutation.entidadeTipo === "CONVERSA" &&
      mutation.operacao === "CRIAR_CONVERSA" &&
      mutation.status !== "SYNCED"
    ) {
      visibleConversationIds.add(mutation.entidadeId);
    }
  }
  const preferences = new Map(
    storedPreferences.map((preference) => [
      preference.conversaId,
      preference,
    ]),
  );
  const messages = storedMessages.filter((message) => {
    const cutoff = preferences.get(message.conversaId)?.limpoAte;
    return visibleConversationIds.has(message.conversaId) &&
      (!cutoff || compararInstantesDoServidor(
        message.criadaNoClienteEm,
        cutoff,
      ) > 0) &&
      message.corpo?.toLocaleLowerCase("pt-BR").includes(normalized);
  });
  return messages
    .sort((left, right) =>
      compararInstantesDoServidor(
        right.criadaNoClienteEm,
        left.criadaNoClienteEm,
      ),
    )
    .map((message) => ({
      ...message,
      anexos: allAttachments
        .filter((attachment) => attachment.mensagemId === message.id)
        .sort((left, right) => left.ordem - right.ordem),
    }));
}

export async function storeServerConversations(
  conversations: ConversationApi[],
  options: {
    authorizedConversationIds?: readonly string[];
    archivedConversationIds?: readonly string[];
    historyCutoffs?: readonly {
      conversationId: string;
      limpoAte: string | null;
    }[];
    requestStartedAt?: string;
    authorizationSnapshotOrdinal?: number;
  } = {},
  guard?: SyncSessionGuard,
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const authorizationSnapshotOrdinal =
    options.authorizedConversationIds === undefined
      ? null
      : options.authorizationSnapshotOrdinal === undefined
        ? await reserveMessagingRequestOrdinal()
        : validMessagingOrdinal(options.authorizationSnapshotOrdinal);
  if (
    options.authorizedConversationIds !== undefined &&
    authorizationSnapshotOrdinal === 0
  ) {
    throw new Error("A ordem do snapshot de autorização é inválida.");
  }
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);
  const rawTransaction = database.transaction(
    [
      "mensagem_autorizacoes",
      "mensagem_conversas",
      "mensagem_preferencias",
      "mensagens",
      "mensagem_anexos",
      "outbox_mutations",
    ],
    "readwrite",
  );
  const guardedTransaction = guard
    ? guardSyncTransaction(rawTransaction, guard)
    : null;
  const transaction = guardedTransaction?.transaction ?? rawTransaction;
  const conversationStore = transaction.objectStore(
    "mensagem_conversas",
  );
  const authorizationStore = transaction.objectStore(
    "mensagem_autorizacoes",
  );
  const requestStartedAt = options.requestStartedAt ?? new Date().toISOString();
  const requestStartedAtEpoch = Date.parse(requestStartedAt);
  if (options.authorizedConversationIds !== undefined) {
    const currentSnapshot = await authorizationStore.get(
      MESSAGING_AUTHORIZATION_SNAPSHOT_ID,
    );
    if (
      currentSnapshot &&
      validMessagingOrdinal(currentSnapshot.ordinal) >
        (authorizationSnapshotOrdinal ?? 0)
    ) {
      if (guardedTransaction) {
        await guardedTransaction.complete();
      } else {
        await transaction.done;
      }
      return;
    }
  }
  for (const conversation of conversations) {
    await conversationStore.put(conversationRecord(conversation));
  }
  if (options.authorizedConversationIds !== undefined) {
    const authorizedIds = new Set(options.authorizedConversationIds);
    for (const current of await authorizationStore.getAll()) {
      if (
        !authorizationControlRecord(current.id) &&
        !authorizedIds.has(current.id)
      ) {
        await authorizationStore.delete(current.id);
      }
    }
    for (const conversationId of authorizedIds) {
      await authorizationStore.put({
        id: conversationId,
        ordinal: authorizationSnapshotOrdinal ?? 0,
      });
    }
    await authorizationStore.put({
      id: MESSAGING_AUTHORIZATION_SNAPSHOT_ID,
      ordinal: authorizationSnapshotOrdinal ?? 0,
    });
  }
  const archivedIds = options.archivedConversationIds === undefined
    ? null
    : new Set(options.archivedConversationIds);
  if (archivedIds) {
    const preferenceStore = transaction.objectStore("mensagem_preferencias");
    for (const conversation of conversations) {
      const current = await preferenceStore.get(conversation.id);
      const currentPending = current
        ? pendenciasDaPreferencia(current)
        : { arquivamento: false, limpeza: false };
      if (currentPending.arquivamento) {
        continue;
      }
      const archiveUpdatedAtEpoch = Date.parse(
        current?.arquivadoAtualizadoEm ?? "",
      );
      if (
        Number.isFinite(archiveUpdatedAtEpoch) &&
        (
          !Number.isFinite(requestStartedAtEpoch) ||
          archiveUpdatedAtEpoch >= requestStartedAtEpoch
        )
      ) {
        continue;
      }
      const serverArchived = archivedIds.has(conversation.id);
      const currentlyArchived = current?.arquivadoEm != null;
      if (serverArchived === currentlyArchived) {
        continue;
      }
      if (!current && !serverArchived) {
        continue;
      }
      await preferenceStore.put(preferenciaComPendencias({
        ...(current ?? {
          conversaId: conversation.id,
          arquivadoEm: null,
          limpoAte: null,
          arquivadoPendente: false,
          limpoPendente: false,
          pendente: false,
        }),
        conversaId: conversation.id,
        arquivadoEm: serverArchived ? requestStartedAt : null,
        arquivadoAtualizadoEm: requestStartedAt,
      }, {
        arquivamento: false,
        limpeza: currentPending.limpeza,
      }));
    }
  }
  if (options.historyCutoffs !== undefined) {
    const preferenceStore = transaction.objectStore("mensagem_preferencias");
    const authorizedIds = new Set(options.authorizedConversationIds ?? []);
    for (const serverPreference of options.historyCutoffs) {
      if (!authorizedIds.has(serverPreference.conversationId)) {
        continue;
      }
      const current = await preferenceStore.get(
        serverPreference.conversationId,
      );
      const currentPending = current
        ? pendenciasDaPreferencia(current)
        : { arquivamento: false, limpeza: false };
      if (currentPending.limpeza) {
        continue;
      }
      const cleanupUpdatedAtEpoch = Date.parse(
        current?.limpoAtualizadoEm ?? "",
      );
      if (
        Number.isFinite(cleanupUpdatedAtEpoch) &&
        (
          !Number.isFinite(requestStartedAtEpoch) ||
          cleanupUpdatedAtEpoch >= requestStartedAtEpoch
        )
      ) {
        continue;
      }
      await preferenceStore.put(preferenciaComPendencias({
        ...(current ?? {
          conversaId: serverPreference.conversationId,
          arquivadoEm: null,
          limpoAte: null,
          arquivadoPendente: false,
          limpoPendente: false,
          pendente: false,
        }),
        conversaId: serverPreference.conversationId,
        limpoAte: serverPreference.limpoAte,
        limpoAtualizadoEm: requestStartedAt,
      }, {
        arquivamento: currentPending.arquivamento,
        limpeza: false,
      }));
    }
  }
  if (options.authorizedConversationIds !== undefined) {
    const authorizedIds = new Set(options.authorizedConversationIds);
    /*
     * A conversa criada aqui e ainda não subida não está na resposta do
     * servidor — ele não a conhece. Apagá-la por isso destruiria a conversa,
     * as mensagens escritas nela e a própria fila que as levaria para cima,
     * tudo em silêncio, na primeira releitura. É a mesma regra da geometria:
     * resposta do servidor não apaga trabalho local que ainda não subiu.
     */
    const aguardandoSubida = new Set<string>();
    for (const mutation of await transaction
      .objectStore("outbox_mutations")
      .getAll()) {
      if (
        mutation.entidadeTipo !== "CONVERSA" ||
        mutation.operacao !== "CRIAR_CONVERSA"
      ) {
        continue;
      }
      if (mutation.status !== "SYNCED") {
        aguardandoSubida.add(mutation.entidadeId);
        continue;
      }
      /*
       * Uma resposta iniciada antes do alias não pode apagar a conversa que
       * acabou de ser adotada. Uma resposta atual, iniciada depois, pode: é
       * assim que revogação e exclusão autoritativas deixam de virar ghosts.
       */
      if (
        Date.parse(requestStartedAt) <= Date.parse(mutation.updatedAt)
      ) {
        aguardandoSubida.add(
          resolvedConversationId(mutation.entidadeId, mutation),
        );
      }
    }
    const [allConversations, allMessages, allAttachments, allPreferences,
      allMutations] = await Promise.all([
      conversationStore.getAll(),
      transaction.objectStore("mensagens").getAll(),
      transaction.objectStore("mensagem_anexos").getAll(),
      transaction.objectStore("mensagem_preferencias").getAll(),
      transaction.objectStore("outbox_mutations").getAll(),
    ]);
    const candidateConversationIds = new Set<string>([
      ...allConversations.map(({ id }) => id),
      ...allMessages.map(({ conversaId }) => conversaId),
      ...allAttachments.map(({ conversaId }) => conversaId),
      ...allPreferences.map(({ conversaId }) => conversaId),
      ...allMutations.flatMap((mutation) =>
        typeof mutation.payload.conversaId === "string"
          ? [mutation.payload.conversaId]
          : []),
    ]);
    for (const conversationId of candidateConversationIds) {
      if (
        authorizedIds.has(conversationId) ||
        aguardandoSubida.has(conversationId)
      ) {
        continue;
      }
      const messages = await transaction
        .objectStore("mensagens")
        .index("by-conversation-id")
        .getAll(conversationId);
      const attachments = await transaction
        .objectStore("mensagem_anexos")
        .index("by-conversation-id")
        .getAll(conversationId);
      const messageIds = new Set(messages.map((message) => message.id));
      const attachmentIds = new Set(attachments.map(({ id }) => id));
      const uploadMutationIds = new Set(
        attachments.flatMap((attachment) =>
          attachment.uploadMutationId
            ? [attachment.uploadMutationId]
            : [],
        ),
      );
      const outboxStore = transaction.objectStore("outbox_mutations");
      const relatedMutations = allMutations.filter(
        (mutation) =>
          messageIds.has(mutation.entidadeId) ||
          attachmentIds.has(mutation.entidadeId) ||
          uploadMutationIds.has(mutation.clientMutationId) ||
          mutation.payload.conversaId === conversationId ||
          (
            mutation.entidadeTipo === "CONVERSA" &&
            mutation.entidadeId === conversationId
          ),
      );
      const pendingEntityIds = new Set(
        relatedMutations
          .filter((mutation) => mutation.status !== "SYNCED")
          .map((mutation) => mutation.entidadeId),
      );
      const pendingMutationIds = new Set(
        relatedMutations
          .filter((mutation) => mutation.status !== "SYNCED")
          .map((mutation) => mutation.clientMutationId),
      );
      const quarantinedMessageIds = new Set(
        messages
          .filter(
            (message) =>
              message.syncStatus !== "SINCRONIZADO" ||
              pendingEntityIds.has(message.id),
          )
          .map((message) => message.id),
      );
      const quarantinedAttachmentIds = new Set(
        attachments
          .filter(
            (attachment) =>
              attachment.syncStatus !== "SINCRONIZADO" ||
              pendingEntityIds.has(attachment.id) ||
              (
                attachment.uploadMutationId !== null &&
                pendingMutationIds.has(attachment.uploadMutationId)
              ) ||
              quarantinedMessageIds.has(attachment.mensagemId),
          )
          .map((attachment) => attachment.id),
      );
      const quarantineReason =
        "Acesso a conversa revogado; trabalho local preservado para revisao.";
      for (const mutation of relatedMutations) {
        if (mutation.status === "SYNCED") {
          await outboxStore.delete(mutation.clientMutationId);
          continue;
        }
        await outboxStore.put({
          ...mutation,
          status: "REJECTED",
          lastSafeCode: MESSAGING_ACCESS_REVOKED_REVIEW_CODE,
          blockedReason: quarantineReason,
          ultimoErro: quarantineReason,
          conflito: {
            tipo: "MENSAGEM_ACESSO_REVOGADO",
            conversaId: conversationId,
          },
          updatedAt: new Date().toISOString(),
        });
      }
      for (const message of messages) {
        if (quarantinedMessageIds.has(message.id)) {
          await transaction.objectStore("mensagens").put({
            ...message,
            syncStatus: "FALHOU",
            ultimoErro: quarantineReason,
            updatedAt: new Date().toISOString(),
          });
        } else {
          await transaction.objectStore("mensagens").delete(message.id);
        }
      }
      for (const attachment of attachments) {
        if (quarantinedAttachmentIds.has(attachment.id)) {
          await transaction.objectStore("mensagem_anexos").put({
            ...attachment,
            syncStatus: "FALHOU",
            ultimoErro: quarantineReason,
            updatedAt: new Date().toISOString(),
          });
        } else {
          await transaction.objectStore("mensagem_anexos").delete(
            attachment.id,
          );
        }
      }
      const preference = await transaction
        .objectStore("mensagem_preferencias")
        .get(conversationId);
      if (!preference?.pendente) {
        await transaction.objectStore("mensagem_preferencias").delete(
          conversationId,
        );
      }
      await conversationStore.delete(conversationId);
    }
  }
  if (guardedTransaction) {
    await guardedTransaction.complete();
  } else {
    await transaction.done;
  }
  emitMessagesChanged();
}

export async function storeServerMessages(
  messages: MessageApi[],
  guard?: SyncSessionGuard,
  options: StoreServerMessageOptions = {},
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const requestOrdinal = options.requestOrdinal === undefined
    ? await reserveMessagingRequestOrdinal()
    : validMessagingOrdinal(options.requestOrdinal);
  if (requestOrdinal === 0) {
    throw new Error("A ordem da requisição de mensagens é inválida.");
  }
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);
  const rawTransaction = database.transaction(
    [
      "mensagem_autorizacoes",
      "mensagem_conversas",
      "mensagens",
      "mensagem_anexos",
    ],
    "readwrite",
  );
  const guardedTransaction = guard
    ? guardSyncTransaction(rawTransaction, guard)
    : null;
  const transaction = guardedTransaction?.transaction ?? rawTransaction;
  const messageStore = transaction.objectStore("mensagens");
  const attachmentStore = transaction.objectStore("mensagem_anexos");
  const authorizationStore = transaction.objectStore(
    "mensagem_autorizacoes",
  );
  const conversationStore = transaction.objectStore(
    "mensagem_conversas",
  );
  const timestamp = new Date().toISOString();
  const authorizationSnapshot = await authorizationStore.get(
    MESSAGING_AUTHORIZATION_SNAPSHOT_ID,
  );

  for (const message of messages) {
    const authorization = authorizationSnapshot
      ? await authorizationStore.get(message.conversaId)
      : null;
    const authorized = authorizationSnapshot
      ? Boolean(
          authorization &&
          requestOrdinal >= validMessagingOrdinal(authorization.ordinal),
        )
      : Boolean(await conversationStore.get(message.conversaId));
    if (!authorized) {
      continue;
    }
    const existing = await messageStore.get(message.id);
    const byMutation = existing
      ? undefined
      : await messageStore
          .index("by-client-mutation-id")
          .get(message.clientMutationId);
    const local = existing ?? byMutation;
    if (local && local.id !== message.id) {
      await messageStore.delete(local.id);
    }
    await messageStore.put({
      id: message.id,
      conversaId: message.conversaId,
      autorId: message.autorId,
      autorNome: message.autorNome,
      corpo: message.corpo,
      status: message.status,
      clientMutationId: message.clientMutationId,
      criadaNoClienteEm: message.criadaNoClienteEm,
      criadaEm: message.criadaEm,
      editadaEm: message.editadaEm,
      deletadaEm: message.deletadaEm,
      versaoEntidade: message.versao,
      syncStatus: "SINCRONIZADO",
      ultimoErro: null,
      updatedAt: timestamp,
    });

    const localAttachments = await attachmentStore
      .index("by-message-id")
      .getAll(local?.id ?? message.id);
    for (const serverAttachment of message.anexos) {
      const matchingLocal = localAttachments.find(
        (attachment) =>
          attachment.objetoId === serverAttachment.objetoId ||
          attachment.ordem === serverAttachment.ordem,
      );
      if (matchingLocal && matchingLocal.id !== serverAttachment.id) {
        await attachmentStore.delete(matchingLocal.id);
      }
      await attachmentStore.put({
        id: serverAttachment.id,
        mensagemId: message.id,
        conversaId: message.conversaId,
        objetoId: serverAttachment.objetoId,
        uploadMutationId: matchingLocal?.uploadMutationId ?? null,
        nome: serverAttachment.nome,
        mediaType: serverAttachment.mediaType,
        tamanhoBytes: serverAttachment.tamanhoBytes,
        sha256: serverAttachment.sha256,
        ordem: serverAttachment.ordem,
        arquivo: matchingLocal?.arquivo ?? null,
        syncStatus: "SINCRONIZADO",
        ultimoErro: null,
        createdAt: matchingLocal?.createdAt ?? timestamp,
        updatedAt: timestamp,
      });
    }
  }
  if (guardedTransaction) {
    await guardedTransaction.complete();
  } else {
    await transaction.done;
  }
  emitMessagesChanged();
}

export async function retryMessage(messageId: string): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["mensagens", "mensagem_anexos", "outbox_mutations"],
    "readwrite",
  );
  const messageStore = transaction.objectStore("mensagens");
  const attachmentStore = transaction.objectStore("mensagem_anexos");
  const outboxStore = transaction.objectStore("outbox_mutations");
  const message = await messageStore.get(messageId);
  if (!message) {
    transaction.abort();
    throw new Error("Mensagem local não encontrada.");
  }
  const timestamp = new Date().toISOString();
  await messageStore.put({
    ...message,
    syncStatus: "NA_FILA",
    ultimoErro: null,
    updatedAt: timestamp,
  });
  const attachments = await attachmentStore
    .index("by-message-id")
    .getAll(messageId);
  const mutationIds = new Set([
    message.clientMutationId,
    ...attachments.flatMap((attachment) =>
      attachment.uploadMutationId ? [attachment.uploadMutationId] : [],
    ),
  ]);
  for (const mutationId of mutationIds) {
    const mutation = await outboxStore.get(mutationId);
    if (mutation && mutation.status !== "SYNCED") {
      await outboxStore.put({
        ...mutation,
        status: "PENDING",
        ultimoErro: null,
        conflito: null,
        updatedAt: timestamp,
      });
    }
  }
  for (const attachment of attachments) {
    if (attachment.syncStatus !== "SINCRONIZADO") {
      await attachmentStore.put({
        ...attachment,
        syncStatus: "NA_FILA",
        ultimoErro: null,
        updatedAt: timestamp,
      });
    }
  }
  await transaction.done;
  emitMessagesChanged();
  // A mensagem voltou para a fila: o mesmo aviso vale aqui.
  anunciarEscritaLocal();
}

export async function localAttachmentBlob(
  attachmentId: string,
): Promise<Blob | null> {
  const database = await getCortexDb();
  return (await database.get("mensagem_anexos", attachmentId))?.arquivo ?? null;
}

function conversationRecord(
  conversation: ConversationApi,
): ConversaLocalRecord {
  return {
    id: conversation.id,
    tipo: conversation.tipo,
    titulo: conversation.titulo,
    obraId: conversation.obraId,
    equipeId: conversation.equipeId,
    status: conversation.status,
    participantes: conversation.participantes,
    criadaEm: conversation.criadaEm,
    atualizadaEm: conversation.atualizadaEm,
    versaoEntidade: conversation.versao,
  };
}

async function addAttachmentHashes(
  plan: QueuedMessagePlan,
): Promise<QueuedMessagePlan> {
  const attachments = await Promise.all(
    plan.attachments.map(async (attachment) => ({
      ...attachment,
      sha256: attachment.arquivo
        ? await sha256Blob(attachment.arquivo)
        : null,
    })),
  );
  return { ...plan, attachments };
}

export async function sha256Blob(blob: Blob): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", await blob.arrayBuffer()),
  );
  return Array.from(digest, (value) => value.toString(16).padStart(2, "0")).join(
    "",
  );
}

function formatBytes(bytes: number): string {
  return `${Math.round(bytes / 1024 / 1024)} MB`;
}
