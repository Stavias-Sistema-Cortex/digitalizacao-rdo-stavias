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

/**
 * Intervalo entre verificações de atualização do service worker.
 *
 * O navegador só procura um service worker novo em navegação — e uma PWA de
 * campo fica aberta por dias sem navegar. Sem esta verificação, a correção
 * publicada nunca chega a quem mais precisa dela: o aparelho continua com o
 * worker antigo e com os defeitos que ele carrega, e o aviso de atualização
 * jamais aparece. Uma hora equilibra chegada razoável da correção com o custo
 * de rede de quem está no campo.
 */
const INTERVALO_VERIFICACAO_MS = 60 * 60 * 1000;

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
        onRegisteredSW(_url, registration) {
          if (!registration) {
            return;
          }
          window.setInterval(() => {
            // Offline não há o que verificar, e a tentativa só geraria erro
            // de rede no console de quem está em campo.
            if (!navigator.onLine) {
              return;
            }
            void registration.update().catch(() => {
              // Falha transitória de rede: a próxima verificação tenta de
              // novo, e o aviso de atualização continua aparecendo quando
              // uma versão nova for encontrada.
            });
          }, INTERVALO_VERIFICACAO_MS);
        },
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
