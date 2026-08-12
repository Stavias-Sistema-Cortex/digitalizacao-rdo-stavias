import {
  getOutboxMutation,
  listReadyPendingOutboxMutations,
} from "../db/outboxRepository";
import type { OutboxMutationRecord } from "../db/db.types";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
  reconcileCanonicalConflict,
  rejectMutationLocally,
  returnMutationToPending,
} from "./syncStorage";
import { pushMutationsApi } from "./syncApiClient";
import {
  toPushMutationRequest,
  type SyncPushMutationRequest,
  type SyncPushMutationResult,
} from "./sync.types";
import { retryDispositionForResult } from "./automaticSyncRetryStorage";
import { classifyAutomaticRequestFailure } from "./automaticRequestFailure";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "./syncSession";

export interface PushOutboxSummary {
  pushed: number;
  applied: number;
  errors: number;
  retryableErrors: number;
  conflicts: number;
  appliedMutationIds: readonly string[];
  handledMutationIds: readonly string[];
  errorMutationIds: readonly string[];
  /**
   * Conflitos que a fusão por campo já resolveu, e a substituta que responde
   * por cada um.
   *
   * Sem essa lista o motor não tinha como saber que havia trabalho novo pronto
   * para subir, e a substituta esperava a próxima janela — meia dúzia de
   * segundos de impasse virava meio minuto de tarja vermelha por um conflito
   * que já estava resolvido.
   */
  reconciledReplacementByOriginalId: ReadonlyMap<string, string>;
}

interface PreparedMutation {
  row: OutboxMutationRecord;
  request: SyncPushMutationRequest;
}

export async function pushOutbox(
  deviceId: string,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<PushOutboxSummary> {
  assertSyncSession(guard);
  const pendingMutations = await listReadyPendingOutboxMutations(100);
  assertSyncSession(guard);
  const prepared: PreparedMutation[] = [];
  const handledMutationIds: string[] = [];
  const errorMutationIds: string[] = [];
  let localErrors = 0;

  for (const row of pendingMutations) {
    assertSyncSession(guard);
    let lockedRow: OutboxMutationRecord;
    try {
      lockedRow = await markMutationAsSyncing(row, guard);
    } catch (error: unknown) {
      // Trancar a linha falhou: armazenamento cheio, transação abortada, ou a
      // tela superou esta mutação entre a listagem e a trava. Nada disso diz
      // que o conteúdo está errado, e recusá-la aqui destruía até a marca de
      // superação que outra parte do sistema tinha acabado de escrever.
      assertSyncSession(guard);
      await devolverAFilaSemPerderTrabalho(
        row.clientMutationId,
        "LOCAL_LOCK_FAILED",
        errorMessage(error),
        guard,
      );
      handledMutationIds.push(row.clientMutationId);
      errorMutationIds.push(row.clientMutationId);
      localErrors += 1;
      continue;
    }

    try {
      assertSyncSession(guard);
      const request = await toPushMutationRequest(lockedRow);
      assertSyncSession(guard);
      prepared.push({ row: lockedRow, request });
    } catch (error: unknown) {
      // O envelope não pode ser montado a partir do que está gravado: aqui a
      // linha é o problema, e insistir com ela só devolveria o mesmo erro a
      // cada ciclo. Fica em quarentena, e o botão de reenviar continua sendo a
      // saída para quem quiser tentar de novo depois de uma correção.
      assertSyncSession(guard);
      await rejectMutationLocally(
        row.clientMutationId,
        "LOCAL_CANONICAL_INVALID",
        errorMessage(error),
        guard,
      );
      handledMutationIds.push(row.clientMutationId);
      errorMutationIds.push(row.clientMutationId);
      localErrors += 1;
    }
  }

  if (prepared.length === 0) {
    return {
      pushed: 0,
      applied: 0,
      errors: localErrors,
      retryableErrors: 0,
      conflicts: 0,
      appliedMutationIds: [],
      handledMutationIds,
      errorMutationIds,
      reconciledReplacementByOriginalId: new Map(),
    };
  }

  const handled = new Set<string>();
  const reconciledReplacementByOriginalId = new Map<string, string>();
  try {
    const response = await pushMutationsApi({
      dispositivoId: deviceId,
      mutacoes: prepared.map((item) => item.request),
    });
    assertSyncSession(guard);
    const resultsById = new Map<string, SyncPushMutationResult>();
    for (const result of response.resultados) {
      if (resultsById.has(result.clientMutationId)) {
        throw new Error(
          `O servidor repetiu o resultado ${result.clientMutationId}.`,
        );
      }
      resultsById.set(result.clientMutationId, result);
    }

    let applied = 0;
    let errors = localErrors;
    let retryableErrors = 0;
    let conflicts = 0;
    const appliedMutationIds: string[] = [];

    for (const { row } of prepared) {
      assertSyncSession(guard);
      const result = resultsById.get(row.clientMutationId);
      if (!result) {
        await returnMutationToPending(
          row.clientMutationId,
          "O servidor não retornou resultado para esta mutação.",
          "SERVER_RESULT_MISSING",
          guard,
        );
        handled.add(row.clientMutationId);
        handledMutationIds.push(row.clientMutationId);
        errorMutationIds.push(row.clientMutationId);
        errors += 1;
        retryableErrors += 1;
        continue;
      }

      try {
        await applyPushResultAtomically(result, guard);
        if (
          result.status === "DESCARTADA" ||
          result.status === "CONFLITO"
        ) {
          const substituta = await reconcileCanonicalConflict(
            row.clientMutationId,
            undefined,
            undefined,
            undefined,
            guard,
          );
          if (substituta) {
            reconciledReplacementByOriginalId.set(
              row.clientMutationId,
              substituta.clientMutationId,
            );
          }
        }
      } catch (error: unknown) {
        assertSyncSession(guard);
        await rejectMutationLocally(
          row.clientMutationId,
          "LOCAL_RESULT_APPLY_INVALID",
          errorMessage(error),
          guard,
        );
        handled.add(row.clientMutationId);
        handledMutationIds.push(row.clientMutationId);
        errorMutationIds.push(row.clientMutationId);
        errors += 1;
        continue;
      }
      handled.add(row.clientMutationId);
      handledMutationIds.push(row.clientMutationId);

      if (result.status === "APLICADA") {
        applied += 1;
        appliedMutationIds.push(row.clientMutationId);
      } else if (
        result.status === "DESCARTADA" ||
        result.status === "CONFLITO"
      ) {
        conflicts += 1;
      } else {
        errorMutationIds.push(row.clientMutationId);
        errors += 1;
        if (retryDispositionForResult(result).retryable) {
          retryableErrors += 1;
        }
      }
    }

    return {
      pushed: prepared.length,
      applied,
      errors,
      retryableErrors,
      conflicts,
      appliedMutationIds,
      handledMutationIds,
      errorMutationIds,
      reconciledReplacementByOriginalId,
    };
  } catch (error: unknown) {
    assertSyncSession(guard);
    const failure = classifyAutomaticRequestFailure(error);
    for (const { row } of prepared) {
      if (handled.has(row.clientMutationId)) continue;
      if (failure.retryable || !failure.veredito) {
        // Sem veredito, o lote volta para a fila. É o caso do 403 que o filtro
        // de CSRF devolve sem código, do 404 de uma rota que sumiu num deploy
        // e do 413 de um lote grande demais: nenhuma destas mutações foi lida
        // pelo servidor, e encerrá-las apagaria trabalho que ninguém julgou.
        await returnMutationToPending(
          row.clientMutationId,
          failure.message,
          failure.safeCode,
          guard,
        );
      } else {
        // Veredito nomeado do servidor sobre o lote — acesso negado, envelope
        // inválido. Reenviar repetiria a mesma recusa.
        await rejectMutationLocally(
          row.clientMutationId,
          failure.safeCode,
          failure.message,
          guard,
        );
      }
    }
    throw error;
  }
}

/**
 * Devolve a mutação à fila sem descartar o trabalho de quem a digitou.
 *
 * <p>Esta função existe para separar duas coisas que estavam confundidas: o
 * <b>veredito do servidor sobre uma mutação</b> — que pode, sim, ser terminal
 * — e a <b>falha do caminho até ele</b>, que nunca é. Uma requisição que
 * fracassa não julgou nada: o servidor sequer leu o que foi enviado. Marcar as
 * cem linhas do lote como recusadas por causa de um 403 de CSRF, de um 404 de
 * rota depois de um deploy ou de uma transação local abortada apagava o dia de
 * trabalho de um aparelho inteiro — em silêncio, e sem volta automática,
 * porque recusa terminal não é retentada nem pelo botão de reenviar.
 *
 * <p>Reenviar é seguro: o servidor decide por {@code clientMutationId} e
 * reconhece o que já aplicou, então a repetição não duplica nada. Se a causa
 * for permanente, a linha volta a falhar — mas falha à vista, na tarja, com
 * espera crescente entre as tentativas, em vez de sumir.
 *
 * <p>A linha que deixou de estar em voo — porque a tela a superou ou a
 * descartou enquanto o ciclo corria — não é tocada: revivê-la desfaria uma
 * decisão de quem estava usando o aplicativo.
 */
async function devolverAFilaSemPerderTrabalho(
  clientMutationId: string,
  safeCode: string,
  message: string,
  guard: SyncSessionGuard,
): Promise<void> {
  const atual = await getOutboxMutation(clientMutationId);
  if (!atual) return;
  if (atual.status !== "PENDING" && atual.status !== "SYNCING") return;
  await returnMutationToPending(clientMutationId, message, safeCode, guard);
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Falha desconhecida durante o push.";
}
