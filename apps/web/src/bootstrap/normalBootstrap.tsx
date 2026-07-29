import { StrictMode } from "react";
import type { Root } from "react-dom/client";
import { registerSW } from "virtual:pwa-register";

import App from "../App";
import { PwaUpdatePrompt } from "../components/PwaUpdatePrompt";
import {
  createPwaUpdatePromptController,
} from "../components/pwaUpdatePromptController";
import { initializeAuthSession } from "../features/auth/authService";
import { initializeCortexDb } from "../lib/db/cortexDb";

export async function mountNormalCortex(root: Root): Promise<void> {
  const pwaUpdateController =
    createPwaUpdatePromptController();
  pwaUpdateController.register(registerSW);

  let authUnavailable = false;
  try {
    const session = await initializeAuthSession();
    if (session) {
      await initializeCortexDb();
    }
  } catch {
    authUnavailable = true;
    // Sem sessão online válida, o App apresenta o acesso apropriado ao modo.
  }

  root.render(
    <StrictMode>
      <App initialAuthUnavailable={authUnavailable} />
      <PwaUpdatePrompt controller={pwaUpdateController} />
    </StrictMode>,
  );
}
