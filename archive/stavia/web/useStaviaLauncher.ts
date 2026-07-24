import { useContext } from "react";

import {
  StaviaLauncherContext,
  type StaviaLauncherContextValue,
} from "./staviaLauncherContext";

export function useStaviaLauncher(): StaviaLauncherContextValue {
  const context = useContext(StaviaLauncherContext);

  if (!context) {
    throw new Error(
      "useStaviaLauncher precisa estar dentro de StaviaLauncherProvider.",
    );
  }

  return context;
}
