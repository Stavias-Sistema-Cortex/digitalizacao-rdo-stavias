import { getSyncState } from "../db/syncStateRepository";
import { pullEventsApi } from "./syncApiClient";
import { applyPulledEventsAtomically } from "./syncStorage";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "./syncSession";

const PAGE_LIMIT = 100;
const MAX_PAGES_PER_RUN = 50;

export interface PullEventsSummary {
  pulled: number;
  lastAppliedCommitSeq: number;
  messagingConversationIds: string[];
}

export async function pullEvents(
  deviceId: string,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<PullEventsSummary> {
  assertSyncSession(guard);
  const initialState = await getSyncState(guard);
  assertSyncSession(guard);

  let cursor = initialState.lastPulledCommitSeq;
  let pulled = 0;
  let page = 0;
  const messagingConversationIds = new Set<string>();

  while (page < MAX_PAGES_PER_RUN) {
    const response = await pullEventsApi(
      cursor,
      deviceId,
      PAGE_LIMIT,
    );
    assertSyncSession(guard);

    const newCursor =
      await applyPulledEventsAtomically(
        response.eventos,
        response.nextCommitSeq,
        guard,
      );
    assertSyncSession(guard);

    pulled += response.eventos.length;
    for (const event of response.eventos) {
      if (
        (event.entidadeTipo === "CONVERSA" ||
          event.entidadeTipo === "MENSAGEM" ||
          event.entidadeTipo === "MENSAGEM_ANEXO") &&
        typeof event.payload?.conversaId === "string"
      ) {
        messagingConversationIds.add(event.payload.conversaId);
      }
    }

    if (newCursor < cursor) {
      throw new Error(
        "O cursor de pull tentou regredir.",
      );
    }

    if (
      response.hasMore &&
      newCursor === cursor
    ) {
      throw new Error(
        "O servidor informou mais eventos, mas o cursor não avançou.",
      );
    }

    cursor = newCursor;
    page += 1;

    if (!response.hasMore) {
      return {
        pulled,
        lastAppliedCommitSeq: cursor,
        messagingConversationIds: [...messagingConversationIds],
      };
    }
  }

  throw new Error(
    `Pull interrompido após ${MAX_PAGES_PER_RUN} páginas para evitar loop infinito.`,
  );
}
