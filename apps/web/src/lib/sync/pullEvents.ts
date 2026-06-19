import { getSyncState } from "../db/syncStateRepository";
import { pullEventsApi } from "./syncApiClient";
import { applyPulledEventsAtomically } from "./syncStorage";

const PAGE_LIMIT = 100;
const MAX_PAGES_PER_RUN = 50;

export interface PullEventsSummary {
  pulled: number;
  lastAppliedCommitSeq: number;
}

export async function pullEvents(): Promise<PullEventsSummary> {
  const initialState = await getSyncState();

  let cursor = initialState.lastPulledCommitSeq;
  let pulled = 0;
  let page = 0;

  while (page < MAX_PAGES_PER_RUN) {
    const response = await pullEventsApi(
      cursor,
      PAGE_LIMIT,
    );

    const newCursor =
      await applyPulledEventsAtomically(
        response.eventos,
        response.nextCommitSeq,
      );

    pulled += response.eventos.length;

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
      };
    }
  }

  throw new Error(
    `Pull interrompido após ${MAX_PAGES_PER_RUN} páginas para evitar loop infinito.`,
  );
}
