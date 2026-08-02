import { useSyncExternalStore } from "react";

import "./PwaUpdatePrompt.css";
import type {
  PwaUpdatePromptController,
} from "./pwaUpdatePromptController";

type PwaUpdatePromptProps = {
  controller: PwaUpdatePromptController;
};

export function PwaUpdatePrompt({
  controller,
}: PwaUpdatePromptProps) {
  const needsRefresh = useSyncExternalStore(
    controller.subscribe,
    controller.getSnapshot,
    controller.getSnapshot,
  );

  if (!needsRefresh) {
    return null;
  }

  return (
    <aside
      aria-label="Atualização disponível"
      className="pwa-update-prompt"
      role="status"
    >
      <p className="pwa-update-prompt__message">
        Nova versão pronta.
      </p>
      <button
        className="pwa-update-prompt__action"
        onClick={() => {
          void controller.applyUpdate();
        }}
        type="button"
      >
        Atualizar
      </button>
    </aside>
  );
}
