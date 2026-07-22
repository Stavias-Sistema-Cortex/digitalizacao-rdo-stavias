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

export async function refreshConversationList(): Promise<void> {
  const conversations = await listConversationsApi(100);
  await storeServerConversations(conversations, {
    authoritative: true,
  });
}

export async function refreshConversationHistory(
  conversationId: string,
  guard?: SyncSessionGuard,
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const messages = await getMessageHistoryApi(conversationId, 100);
  if (guard) assertSyncSession(guard);
  await storeServerMessages(messages);
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
  await storeServerConversations(conversations, {
    authoritative: true,
  });
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
