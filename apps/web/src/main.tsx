import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { registerSW } from "virtual:pwa-register";

import App from "./App.tsx";
import "./index.css";

import { initializeCortexDb } from "./lib/db/cortexDb";

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
  await initializeCortexDb();

  const rootElement = document.getElementById("root");

  if (!rootElement) {
    throw new Error(
      "Elemento HTML #root não foi encontrado.",
    );
  }

  createRoot(rootElement).render(
    <StrictMode>
      <App />
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
      "Não foi possível inicializar o armazenamento local.";
  }
});
