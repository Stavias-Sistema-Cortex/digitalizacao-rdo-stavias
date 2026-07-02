import {
  useEffect,
  useRef,
} from "react";

import { getSession } from "../../features/auth/authSession";
import { syncNow } from "./syncEngine";

const SYNC_INTERVAL_MS = 30_000;

type AutomaticSyncTrigger =
  | "STARTUP"
  | "ONLINE"
  | "INTERVAL"
  | "VISIBILITY";

export function useAutomaticSync(): void {
  const lastReportedErrorRef =
    useRef<string | null>(null);

  useEffect(() => {
    let disposed = false;

    async function tryAutomaticSync(
      trigger: AutomaticSyncTrigger,
    ): Promise<void> {
      if (disposed || !navigator.onLine) {
        return;
      }

      if (!getSession()?.token) {
        return;
      }

      try {
        const summary = await syncNow();

        lastReportedErrorRef.current = null;

        if (!disposed) {
          console.info(
            `[sync automático:${trigger}] concluído`,
            summary,
          );
        }
      } catch (error: unknown) {
        if (disposed) {
          return;
        }

        const message =
          error instanceof Error
            ? error.message
            : "Erro desconhecido na sincronização automática.";

        if (
          lastReportedErrorRef.current !== message
        ) {
          console.warn(
            `[sync automático:${trigger}] ${message}`,
          );

          lastReportedErrorRef.current = message;
        }
      }
    }

    function handleOnline(): void {
      void tryAutomaticSync("ONLINE");
    }

    function handleVisibilityChange(): void {
      if (
        document.visibilityState === "visible"
      ) {
        void tryAutomaticSync("VISIBILITY");
      }
    }

    window.addEventListener(
      "online",
      handleOnline,
    );

    document.addEventListener(
      "visibilitychange",
      handleVisibilityChange,
    );

    const intervalId = window.setInterval(
      () => {
        void tryAutomaticSync("INTERVAL");
      },
      SYNC_INTERVAL_MS,
    );

    void tryAutomaticSync("STARTUP");

    return () => {
      disposed = true;

      window.removeEventListener(
        "online",
        handleOnline,
      );

      document.removeEventListener(
        "visibilitychange",
        handleVisibilityChange,
      );

      window.clearInterval(intervalId);
    };
  }, []);
}
