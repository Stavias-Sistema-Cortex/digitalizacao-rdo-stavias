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
  /**
   * Ainda há evento do servidor além do que coube nesta janela.
   *
   * <p>O teto de páginas existe para uma janela não durar para sempre, e ele é
   * atingido de verdade: um aparelho novo começa no cursor zero e precisa
   * atravessar o histórico inteiro da empresa antes de estar em dia.
   */
  pendente: boolean;
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
        pendente: false,
      };
    }
  }

  /*
   * Gastar o teto de páginas não é falha: é uma janela cheia, com o cursor
   * gravado e o resto esperando a próxima. Aqui isso era uma exceção, e a
   * exceção derrubava o ciclo inteiro — o estado terminava em erro, a tarja
   * vermelha acendia e `announceSyncCompleted` nunca era disparado, então
   * nenhuma tela recarregava com o que tinha acabado de chegar.
   *
   * Quem sentia era exatamente quem acabou de entrar. Um aparelho já em dia
   * traz dezenas de eventos por janela e nunca encosta no teto; um aparelho
   * novo começa no cursor zero, atravessa o histórico inteiro e falha em toda
   * sincronização até alcançá-lo — o que, visto de fora, é "erro de
   * sincronização o tempo todo, e não vejo todas as informações".
   *
   * O guarda contra laço continua onde sempre esteve, e é ele que de fato
   * protege: cursor que regride e cursor que não avança com `hasMore` seguem
   * lançando, dentro do laço, porque nenhum dos dois progride.
   */
  return {
    pulled,
    lastAppliedCommitSeq: cursor,
    messagingConversationIds: [...messagingConversationIds],
    pendente: true,
  };
}
