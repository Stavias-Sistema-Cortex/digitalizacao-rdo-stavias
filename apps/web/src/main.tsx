import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { registerSW } from "virtual:pwa-register";

import App from "./App.tsx";
import "./index.css";

import { initializeCortexDb } from "./lib/db/cortexDb";
import { initializeAuthSession } from "./features/auth/authService";

registerSW({
  immediate: true,
  onRegisterError(error: unknown) {
    console.warn(
      "Não foi possível registrar o modo offline da aplicação.",
      error,
    );
  },
});

async function bootstrap(): Promise<void> {
  let authUnavailable = false;
  try {
    const session = await initializeAuthSession();
    if (session) {
      await initializeCortexDb();
    }
  } catch {
    authUnavailable = true;
    // Sem sessão online válida, o App apresenta o login. Dados locais ficam
    // intactos e o desbloqueio offline será tratado pelo cofre PRF dedicado.
  }

  const rootElement = document.getElementById("root");

  if (!rootElement) {
    throw new Error(
      "Elemento HTML #root não foi encontrado.",
    );
  }

  createRoot(rootElement).render(
    <StrictMode>
      <App initialAuthUnavailable={authUnavailable} />
    </StrictMode>,
  );
}

bootstrap().catch((error: unknown) => {
  console.error(
    "Falha ao inicializar o frontend do Córtex.",
    error,
  );

  const rootElement = document.getElementById("root");

  if (rootElement) {
    rootElement.textContent =
      "Não foi possível inicializar a aplicação com segurança.";
  }
});
