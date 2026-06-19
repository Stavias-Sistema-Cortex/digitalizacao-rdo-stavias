import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import App from "./App.tsx";
import "./index.css";

import { initializeCortexDb } from "./lib/db/cortexDb";

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
