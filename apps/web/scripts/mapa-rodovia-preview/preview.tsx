/**
 * Harness de verificação visual do Mapa da Rodovia.
 *
 * Monta os componentes reais sobre a camada de fixtures declarada em
 * `vite.config.ts`, sem servidor nem banco. Existe para que a composição do
 * mapa possa ser medida em navegador de verdade — é o único caminho para
 * conferir que o painel vetorial realmente pinta, já que nenhum teste de
 * unidade executa WebGL.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { Preview } from "./Preview";
import "../../src/index.css";

const container = document.getElementById("root");
if (!container) {
  throw new Error("Harness sem elemento raiz.");
}

createRoot(container).render(
  <StrictMode>
    <Preview />
  </StrictMode>,
);
