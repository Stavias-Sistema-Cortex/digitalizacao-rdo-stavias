import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { Preview } from "./Preview";
import "../../src/index.css";

createRoot(document.getElementById("root") as HTMLElement).render(
  <StrictMode>
    <Preview />
  </StrictMode>,
);
