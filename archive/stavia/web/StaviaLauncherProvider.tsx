import {
  useCallback,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { StaviaPanel } from "./StaviaPanel";
import {
  StaviaLauncherContext,
  type StaviaLauncherTarget,
} from "./staviaLauncherContext";

interface StaviaLauncherProviderProps {
  children: ReactNode;
}

/**
 * Mantém um único StaviaPanel flutuante acima de todas as rotas:
 * o launcher fica fixo em qualquer tela e as páginas apenas
 * informam o contexto (obra/RDO) pelo hook useStaviaLauncher.
 */
export function StaviaLauncherProvider({
  children,
}: StaviaLauncherProviderProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [target, setTarget] = useState({
    obraId: "",
    rdoId: "",
  });

  const setStaviaContext = useCallback(
    (next: StaviaLauncherTarget) => {
      setTarget((current) => {
        const obraId = next.obraId ?? "";
        const rdoId = next.rdoId ?? "";

        if (
          current.obraId === obraId &&
          current.rdoId === rdoId
        ) {
          return current;
        }

        return { obraId, rdoId };
      });
    },
    [],
  );

  const openStavia = useCallback(
    (next?: StaviaLauncherTarget) => {
      if (next) {
        setStaviaContext(next);
      }
      setIsOpen(true);
    },
    [setStaviaContext],
  );

  const closeStavia = useCallback(() => {
    setIsOpen(false);
  }, []);

  const value = useMemo(
    () => ({
      isOpen,
      openStavia,
      closeStavia,
      setStaviaContext,
    }),
    [isOpen, openStavia, closeStavia, setStaviaContext],
  );

  return (
    <StaviaLauncherContext.Provider value={value}>
      {children}
      <StaviaPanel
        key={`stavia-global:${target.obraId}:${target.rdoId}`}
        variant="floating"
        isOpen={isOpen}
        onOpenChange={setIsOpen}
        initialObraId={target.obraId}
        initialRdoId={target.rdoId}
      />
    </StaviaLauncherContext.Provider>
  );
}
