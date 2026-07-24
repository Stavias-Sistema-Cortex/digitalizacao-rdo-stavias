import { createContext, useContext } from "react";
import type { ReactNode } from "react";

const CortexShellChromeContext = createContext<ReactNode>(null);

interface CortexShellChromeProviderProps {
  children: ReactNode;
  headerControls: ReactNode;
}

export function CortexShellChromeProvider({
  children,
  headerControls,
}: CortexShellChromeProviderProps) {
  return (
    <CortexShellChromeContext.Provider value={headerControls}>
      {children}
    </CortexShellChromeContext.Provider>
  );
}

export function CortexShellHeaderControls() {
  return useContext(CortexShellChromeContext);
}
