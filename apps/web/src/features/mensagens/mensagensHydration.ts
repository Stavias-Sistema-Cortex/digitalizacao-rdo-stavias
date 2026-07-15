import {
  getMessageHistoryApi,
  listConversationsApi,
} from "./mensagensApi";
import {
  storeServerConversations,
  storeServerMessages,
} from "./mensagensRepository";

export async function refreshConversationList(): Promise<void> {
  const conversations = await listConversationsApi(100);
  await storeServerConversations(conversations, {
    authoritative: true,
  });
}

export async function refreshConversationHistory(
  conversationId: string,
): Promise<void> {
  await storeServerMessages(
    await getMessageHistoryApi(conversationId, 100),
  );
}

export async function refreshMessagingAfterPull(
  conversationIds: string[],
): Promise<void> {
  if (conversationIds.length === 0) {
    return;
  }
  const conversations = await listConversationsApi(100);
  await storeServerConversations(conversations, {
    authoritative: true,
  });
  const authorized = new Set(
    conversations.map((conversation) => conversation.id),
  );
  for (const conversationId of [...new Set(conversationIds)]) {
    if (authorized.has(conversationId)) {
      await refreshConversationHistory(conversationId);
    }
  }
}
