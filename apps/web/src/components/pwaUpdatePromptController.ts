import type { RegisterSWOptions } from "virtual:pwa-register";

type UpdateServiceWorker = (
  reloadPage?: boolean,
) => Promise<void>;

type RegisterServiceWorker = (
  options?: RegisterSWOptions,
) => UpdateServiceWorker;

export type PwaUpdatePromptController = {
  applyUpdate: () => Promise<void>;
  getSnapshot: () => boolean;
  register: (
    registerServiceWorker: RegisterServiceWorker,
  ) => void;
  subscribe: (listener: () => void) => () => void;
};

export function createPwaUpdatePromptController(): PwaUpdatePromptController {
  let needsRefresh = false;
  let updateServiceWorker: UpdateServiceWorker | null = null;
  const listeners = new Set<() => void>();

  function showPrompt() {
    if (needsRefresh) {
      return;
    }

    needsRefresh = true;
    listeners.forEach((listener) => listener());
  }

  return {
    applyUpdate() {
      if (!updateServiceWorker) {
        return Promise.resolve();
      }

      return updateServiceWorker(true);
    },
    getSnapshot() {
      return needsRefresh;
    },
    register(registerServiceWorker) {
      updateServiceWorker = registerServiceWorker({
        immediate: true,
        onNeedRefresh: showPrompt,
        onRegisterError(error: unknown) {
          console.warn(
            "Não foi possível registrar o modo offline da aplicação.",
            error,
          );
        },
      });
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
