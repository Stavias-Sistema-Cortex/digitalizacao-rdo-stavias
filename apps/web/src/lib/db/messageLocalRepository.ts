import { getCortexDb } from "./cortexDb";
import type {
  LocalMessageAttachmentRecord,
  LocalMessageRecord,
  LocalMessageReferenceRecord,
} from "./db.types";

export async function getLocalMessage(
  messageId: string,
): Promise<LocalMessageRecord | undefined> {
  const database = await getCortexDb();
  return database.get("messages", messageId);
}

export async function listLocalMessages(
  conversationId: string,
): Promise<LocalMessageRecord[]> {
  const database = await getCortexDb();
  const range = IDBKeyRange.bound(
    [conversationId, "", ""],
    [conversationId, "\uffff", "\uffff"],
  );

  return database.getAllFromIndex(
    "messages",
    "by-conversation-order",
    range,
  );
}

export async function listLocalMessageReferences(
  messageId: string,
): Promise<LocalMessageReferenceRecord[]> {
  const database = await getCortexDb();
  return database.getAllFromIndex(
    "message_references",
    "by-message-id",
    messageId,
  );
}

export async function listLocalMessageAttachments(
  messageId: string,
): Promise<LocalMessageAttachmentRecord[]> {
  const database = await getCortexDb();
  return database.getAllFromIndex(
    "message_attachments",
    "by-message-id",
    messageId,
  );
}

export async function putLocalMessageProjection(
  message: LocalMessageRecord,
  references: LocalMessageReferenceRecord[],
  attachments: LocalMessageAttachmentRecord[],
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["messages", "message_references", "message_attachments"],
    "readwrite",
  );
  const referenceStore = transaction.objectStore("message_references");
  const attachmentStore = transaction.objectStore("message_attachments");

  await transaction.objectStore("messages").put(message);

  const referenceKeys = await referenceStore
    .index("by-message-id")
    .getAllKeys(message.id);
  await Promise.all(referenceKeys.map((key) => referenceStore.delete(key)));
  await Promise.all(references.map((item) => referenceStore.put(item)));

  const existingAttachments = await attachmentStore
    .index("by-message-id")
    .getAll(message.id);
  const existingById = new Map(
    existingAttachments.map((item) => [item.id, item]),
  );
  await Promise.all(
    attachments.map((item) =>
      attachmentStore.put({
        ...item,
        arquivo: item.arquivo ?? existingById.get(item.id)?.arquivo ?? null,
      }),
    ),
  );

  await transaction.done;
}
