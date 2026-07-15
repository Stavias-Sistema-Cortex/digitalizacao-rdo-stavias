import { getCortexDb } from "./cortexDb";
import type {
  LocalConversationParticipantRecord,
  LocalConversationRecord,
} from "./db.types";

export async function getLocalConversation(
  conversationId: string,
): Promise<LocalConversationRecord | undefined> {
  const database = await getCortexDb();
  return database.get("conversations", conversationId);
}

export async function listLocalConversations(): Promise<
  LocalConversationRecord[]
> {
  const database = await getCortexDb();
  const records = await database.getAllFromIndex(
    "conversations",
    "by-activity",
  );

  return records.reverse();
}

export async function putLocalConversation(
  conversation: LocalConversationRecord,
  participants?: LocalConversationParticipantRecord[],
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["conversations", "conversation_participants"],
    "readwrite",
  );

  await transaction.objectStore("conversations").put(conversation);

  if (participants) {
    const participantStore = transaction.objectStore(
      "conversation_participants",
    );
    const existingKeys = await participantStore
      .index("by-conversation-id")
      .getAllKeys(conversation.id);

    await Promise.all(existingKeys.map((key) => participantStore.delete(key)));
    await Promise.all(participants.map((item) => participantStore.put(item)));
  }

  await transaction.done;
}

export async function listLocalConversationParticipants(
  conversationId: string,
): Promise<LocalConversationParticipantRecord[]> {
  const database = await getCortexDb();
  return database.getAllFromIndex(
    "conversation_participants",
    "by-conversation-id",
    conversationId,
  );
}
