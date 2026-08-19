import {
  arquivarConversaApi,
  getConversationAuthorizationSnapshotApi,
  getMessageHistoryApi,
  limparConversaApi,
  listConversationsApi,
  type ConversationApi,
} from "./mensagensApi";
import {
  confirmarPreferenciaDaConversaSincronizada,
  listarPreferenciasPendentesDaConversa,
  reserveMessagingRequestOrdinal,
  resolveLocalConversationId,
  storeServerConversations,
  storeServerMessages,
} from "./mensagensRepository";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "../../lib/sync/syncSession";
import { ApiError } from "../../lib/api/apiError";
import { camposPendentesDaPreferencia } from "./mensagemPreferenciaPendente";

const MESSAGING_AUTHORIZATION_SNAPSHOT_LOCK =
  "cortex-messaging-authorization-snapshot-v1";
let fallbackSnapshotLockTail: Promise<void> = Promise.resolve();

async function runWithAuthorizationSnapshotLock<T>(
  task: () => Promise<T>,
): Promise<T> {
  const locks = typeof navigator !== "undefined" && "locks" in navigator
    ? navigator.locks
    : null;
  if (locks) {
    return locks.request(
      MESSAGING_AUTHORIZATION_SNAPSHOT_LOCK,
      { mode: "exclusive" },
      async () => task(),
    );
  }

  // Ambientes sem Web Locks ainda não podem reordenar duas hidratações do
  // mesmo documento. No Edge instalado, Web Locks estende a mesma seção
  // crítica a todas as abas do mesmo origin.
  const predecessor = fallbackSnapshotLockTail;
  let release!: () => void;
  fallbackSnapshotLockTail = new Promise<void>((resolve) => {
    release = resolve;
  });
  await predecessor;
  try {
    return await task();
  } finally {
    release();
  }
}

export async function refreshConversationList(): Promise<void> {
  await loadConversationSnapshot();
}

interface ConversationHydrationSnapshot {
  conversations: ConversationApi[];
  authorizedIds: string[];
  options: {
    authorizedConversationIds: string[];
    archivedConversationIds: string[];
    historyCutoffs: Array<{
      conversationId: string;
      limpoAte: string | null;
    }>;
    requestStartedAt: string;
    authorizationSnapshotOrdinal: number;
  };
}

async function loadConversationSnapshot(
  guard?: SyncSessionGuard,
): Promise<
  ConversationHydrationSnapshot
> {
  const requestStartedAt = new Date().toISOString();
  const [active, archived] = await Promise.all([
    listConversationsApi(100, false),
    // A gaveta continua utilizável offline. Se este recorte falhar, o retrato
    // completo de IDs ainda preserva o que já estava no aparelho.
    listConversationsApi(100, true).catch(() => [] as ConversationApi[]),
  ]);
  if (guard) assertSyncSession(guard);
  return runWithAuthorizationSnapshotLock(async () => {
    if (guard) assertSyncSession(guard);
    const authorizationSnapshotOrdinal =
      await reserveMessagingRequestOrdinal();
    if (guard) assertSyncSession(guard);
    // A reserva e a leitura autoritativa são vizinhas dentro do lock. Assim a
    // ordem persistida é também a ordem em que o servidor observa as abas.
    const authorization = await getConversationAuthorizationSnapshotApi();
    if (guard) assertSyncSession(guard);
    const authorizedIds = authorization.authorizedConversationIds;
    const byId = new Map<string, ConversationApi>();
    for (const conversation of [...active, ...archived]) {
      byId.set(conversation.id, conversation);
    }
    const archivedConversationIds = archived.map(
      (conversation) => conversation.id,
    );
    const snapshot = {
      conversations: [...byId.values()],
      authorizedIds,
      options: {
        authorizedConversationIds: authorizedIds,
        archivedConversationIds,
        historyCutoffs: authorization.preferences,
        requestStartedAt,
        authorizationSnapshotOrdinal,
      },
    };
    await storeServerConversations(
      snapshot.conversations,
      snapshot.options,
      guard,
    );
    return snapshot;
  });
}

/**
 * O servidor ainda não conhece esta conversa?
 *
 * <p>Uma conversa criada no aparelho existe primeiro aqui: ela aparece na
 * lista, é possível abri-la e escrever nela, e só depois a fila leva a criação.
 * No intervalo, pedir o histórico dela devolve 404 — a resposta correta para
 * uma pergunta que ainda não faz sentido.
 *
 * <p>Quem chama isto está decidindo se um 404 é notícia. Na hidratação de
 * histórico não é: não há o que baixar, e o que está no aparelho continua
 * valendo. Em qualquer outro lugar o 404 segue sendo falha, e por isso a
 * decisão é de quem chama, e não desta função.
 */
export function conversaAindaNaoExisteNoServidor(cause: unknown): boolean {
  return cause instanceof ApiError && cause.status === 404;
}

export async function refreshConversationHistory(
  conversationId: string,
  guard?: SyncSessionGuard,
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const requestOrdinal = await reserveMessagingRequestOrdinal();
  if (guard) assertSyncSession(guard);
  const messages = await getMessageHistoryApi(conversationId, 100);
  if (guard) assertSyncSession(guard);
  await storeServerMessages(messages, guard, { requestOrdinal });
  if (guard) assertSyncSession(guard);
}

export async function refreshMessagingAfterPull(
  conversationIds: string[],
  guard?: SyncSessionGuard,
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const snapshot = await loadConversationSnapshot(guard);
  if (guard) assertSyncSession(guard);
  const authorized = new Set(snapshot.authorizedIds);
  for (const conversationId of [...new Set(conversationIds)]) {
    if (authorized.has(conversationId)) {
      await refreshConversationHistory(conversationId, guard);
    }
  }
}

export interface PendingConversationPreferenceFlushSummary {
  inspected: number;
  applied: number;
  errors: number;
}

/**
 * Envia a arrumação feita offline sem misturá-la à outbox de conteúdo.
 *
 * <p>Arquivar e limpar são preferências idempotentes e pessoais. Cada item é
 * isolado: uma conversa ainda provisória ou uma falha de rede não impede as
 * demais nem interrompe o push/pull principal.</p>
 */
export async function flushPendingConversationPreferences(
  guard?: SyncSessionGuard,
): Promise<PendingConversationPreferenceFlushSummary> {
  if (guard) assertSyncSession(guard);
  const pending = await listarPreferenciasPendentesDaConversa(guard);
  const summary: PendingConversationPreferenceFlushSummary = {
    inspected: pending.length,
    applied: 0,
    errors: 0,
  };
  for (const preference of pending) {
    try {
      if (guard) assertSyncSession(guard);
      const conversationId = await resolveLocalConversationId(
        preference.conversaId,
      );
      const canonicalPreference = {
        ...preference,
        conversaId: conversationId,
      };
      const fields = camposPendentesDaPreferencia(canonicalPreference);
      let confirmedAll = fields.length > 0;
      for (const field of fields) {
        if (guard) assertSyncSession(guard);
        if (field === "ARQUIVAMENTO") {
          await arquivarConversaApi(
            conversationId,
            canonicalPreference.arquivadoEm !== null,
          );
        } else {
          if (canonicalPreference.limpoAte === null) {
            await limparConversaApi(conversationId, false);
          } else {
            await limparConversaApi(
              conversationId,
              true,
              canonicalPreference.limpoAte,
            );
          }
        }
        if (guard) assertSyncSession(guard);
        const confirmed = await confirmarPreferenciaDaConversaSincronizada(
          canonicalPreference,
          guard,
          [field],
        );
        confirmedAll = confirmedAll && confirmed;
      }
      if (confirmedAll) summary.applied += 1;
    } catch {
      // Sessão substituída é boundary, não falha isolável deste item.
      if (guard) assertSyncSession(guard);
      summary.errors += 1;
    }
  }
  return summary;
}
