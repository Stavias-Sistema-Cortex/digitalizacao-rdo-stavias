import { useEffect, useRef } from "react";

import { hasOnlineSession } from "../../features/auth/authSession";
import { loadEarliestAutomaticRetryAt } from "./automaticSyncRetryStorage";
import { createAutomaticSyncScheduler } from "./automaticSyncScheduler";
import { syncNow } from "./syncEngine";

export function useAutomaticSync(enabled = true): void {
  const lastReportedErrorRef = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled) return;
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession,
      loadNextRetryAt: loadEarliestAutomaticRetryAt,
      onSuccess: (trigger, summary) => {
        lastReportedErrorRef.current = null;
        console.info(`[sync automático:${trigger}] concluído`, summary);
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
    return () => scheduler.dispose();
  }, [enabled]);
}
