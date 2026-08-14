import {
  getMessageHistoryApi,
  listConversationsApi,
} from "./mensagensApi";
import {
  storeServerConversations,
  storeServerMessages,
} from "./mensagensRepository";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "../../lib/sync/syncSession";
import { ApiError } from "../../lib/api/apiError";

export async function refreshConversationList(): Promise<void> {
  const conversations = await listConversationsApi(100);
  await storeServerConversations(conversations, {
    authoritative: true,
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
  const messages = await getMessageHistoryApi(conversationId, 100);
  if (guard) assertSyncSession(guard);
  await storeServerMessages(messages, guard);
  if (guard) assertSyncSession(guard);
}

export async function refreshMessagingAfterPull(
  conversationIds: string[],
  guard?: SyncSessionGuard,
): Promise<void> {
  if (conversationIds.length === 0) {
    return;
  }
  if (guard) assertSyncSession(guard);
  const conversations = await listConversationsApi(100);
  if (guard) assertSyncSession(guard);
  await storeServerConversations(
    conversations,
    { authoritative: true },
    guard,
  );
  if (guard) assertSyncSession(guard);
  const authorized = new Set(
    conversations.map((conversation) => conversation.id),
  );
  for (const conversationId of [...new Set(conversationIds)]) {
    if (authorized.has(conversationId)) {
      await refreshConversationHistory(conversationId, guard);
    }
  }
}
