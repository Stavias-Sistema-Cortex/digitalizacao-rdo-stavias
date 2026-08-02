import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import "./index.css";
import { probeActivationOnly } from "./features/auth/activationBootstrap";
import { resolveCortexAuthMode } from "./features/auth/cortexAuthMode";
import { despertarApi } from "./features/auth/despertarApi";

async function bootstrap(): Promise<void> {
  // Primeira linha do dia: o serviço sobe sob demanda, e daqui até alguém
  // terminar de digitar o CPF passam dezenas de segundos que ele pode usar
  // subindo. Não bloqueia nada — é disparado e esquecido.
  despertarApi();

  const rootElement = document.getElementById("root");

  if (!rootElement) {
    throw new Error(
      "Elemento HTML #root não foi encontrado.",
    );
  }

  const root = createRoot(rootElement);
  resolveCortexAuthMode();

  if (await probeActivationOnly()) {
    const { ActivationPage } = await import("./features/auth/ActivationPage");
    root.render(
      <StrictMode>
        <ActivationPage />
      </StrictMode>,
    );
    return;
  }

  const { mountNormalCortex } = await import("./bootstrap/normalBootstrap");
  await mountNormalCortex(root);
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
