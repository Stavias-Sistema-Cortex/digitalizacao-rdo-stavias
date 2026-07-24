import { createContext } from "react";

export interface StaviaLauncherTarget {
  obraId?: string;
  rdoId?: string;
}

export interface StaviaLauncherContextValue {
  isOpen: boolean;
  openStavia: (target?: StaviaLauncherTarget) => void;
  closeStavia: () => void;
  setStaviaContext: (target: StaviaLauncherTarget) => void;
}

export const StaviaLauncherContext =
  createContext<StaviaLauncherContextValue | null>(null);
