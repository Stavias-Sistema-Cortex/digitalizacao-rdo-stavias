import { useEffect, useRef } from "react";

import { hasOnlineSession } from "../../features/auth/authSession";
import { loadEarliestAutomaticRetryAt } from "./automaticSyncRetryStorage";
import { createAutomaticSyncScheduler } from "./automaticSyncScheduler";
import { syncNow } from "./syncEngine";
import { subscribeToSyncCompletedBroadcast } from "./syncEvents";

/**
 * A janela terminou em dia, ou terminou cheia?
 *
 * <p>O resumo chega como `unknown` porque o agendador não conhece o motor que
 * executa; a pergunta é sobre um campo só, e é aqui que ela se responde.
 */
export function aindaFaltaPuxar(summary: unknown): boolean {
  return (
    typeof summary === "object" &&
    summary !== null &&
    (summary as { pullPendente?: unknown }).pullPendente === true
  );
}

export function useAutomaticSync(enabled = true): void {
  const lastReportedErrorRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled) return;
    // Só uma aba executa o ciclo (lease); as demais ficam sabendo por aqui e
    // reapresentam suas telas na hora, em vez de esperar o próprio intervalo.
    const unsubscribeBroadcast = subscribeToSyncCompletedBroadcast();
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession,
      loadNextRetryAt: loadEarliestAutomaticRetryAt,
      onSuccess: (trigger, summary) => {
        lastReportedErrorRef.current = null;
        console.info(`[sync automático:${trigger}] concluído`, summary);
        // Aparelho recém-chegado atravessa o histórico em janelas cheias. Sem
        // este pedido ele esperaria trinta segundos entre cada uma, e a
        // primeira carga levaria dezenas de minutos para completar.
        if (aindaFaltaPuxar(summary)) {
          scheduler.request("PULL_PENDENTE");
        }
      },
      onError: (trigger, error) => {
        const message =
          error instanceof Error
            ? error.message
            : "Erro desconhecido na sincronização automática.";
        if (lastReportedErrorRef.current !== message) {
          console.warn(`[sync automático:${trigger}] ${message}`);
          lastReportedErrorRef.current = message;
        }
      },
    });
    scheduler.start();
    return () => {
      scheduler.dispose();
      unsubscribeBroadcast();
    };
  }, [enabled]);
}
