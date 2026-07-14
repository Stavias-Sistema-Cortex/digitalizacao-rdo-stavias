import { lazy, Suspense, useEffect, useState } from "react";
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useNavigate,
} from "react-router-dom";

import {
  AUTH_SESSION_CHANGED_EVENT,
  getSession,
  hasOnlineSession,
  isAlfa,
} from "./features/auth/authSession";
import { LoginPage } from "./features/auth/LoginPage";
import { DeviceSecurityPage } from "./features/auth/DeviceSecurityPage";
import { OfflineUnlockPage } from "./features/auth/OfflineUnlockPage";
import { loadOfflineVaultMetadata } from "./features/auth/offlineVaultRepository";
import type { OfflineVaultMetadata } from "./features/auth/offlineVault.types";
import { CortexShell } from "./components/shell/CortexShell";
import { HomePage } from "./features/home/HomePage";
import { IntegracoesPage } from "./features/integracoes/IntegracoesPage";
import { ObrasPage } from "./features/obras/ObrasPage";
import { GestaoObrasPage } from "./features/obras/gestao/GestaoObrasPage";
import { RdoWorkspacePage } from "./features/rdos/RdoWorkspacePage";
import { StaviaLauncherProvider } from "./features/stavia/StaviaLauncherProvider";
import { TarefasPage } from "./features/tarefas/TarefasPage";
import { useAutomaticSync } from "./lib/sync/useAutomaticSync";

const MensagensPage = lazy(() =>
  import("./features/mensagens/MensagensPage").then((module) => ({
    default: module.MensagensPage,
  })),
);

function IntegracoesRoute() {
  const navigate = useNavigate();

  return (
    <CortexShell active="integracoes">
      <IntegracoesPage
        onBack={() => {
          navigate("/home");
        }}
      />
    </CortexShell>
  );
}

// Gestão de obras/vínculos é exclusiva do papel Alfa. O guard aqui apenas evita
// exibir a tela; a autorização real é imposta pelo backend em cada endpoint.
function GestaoObrasRoute() {
  if (!isAlfa(getSession())) {
    return <Navigate to="/obras" replace />;
  }

  return (
    <CortexShell active="obras">
      <GestaoObrasPage />
    </CortexShell>
  );
}

type AppProps = {
  initialAuthUnavailable?: boolean;
};

function App({ initialAuthUnavailable = false }: AppProps) {
  const [session, setSession] =
    useState(() => getSession());
  const [online, setOnline] = useState(() => navigator.onLine);
  const [offlineVault, setOfflineVault] =
    useState<OfflineVaultMetadata | null>(null);
  const [vaultChecked, setVaultChecked] = useState(false);

  useAutomaticSync(hasOnlineSession());

  useEffect(() => {
    function refreshSession() {
      setSession(getSession());
    }

    window.addEventListener(
      AUTH_SESSION_CHANGED_EVENT,
      refreshSession,
    );
    return () => {
      window.removeEventListener(
        AUTH_SESSION_CHANGED_EVENT,
        refreshSession,
      );
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    loadOfflineVaultMetadata()
      .then((metadata) => {
        if (!cancelled) {
          setOfflineVault(metadata);
          setVaultChecked(true);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setOfflineVault(null);
          setVaultChecked(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    function handleOnline() {
      setOnline(true);
    }
    function handleOffline() {
      setOnline(false);
    }
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  // Sem sessão online, somente um grant assinado aberto por PRF libera o cache.
  if (!session) {
    if (!vaultChecked) {
      return (
        <main className="auth-bootstrap-status" role="status">
          Verificando o acesso protegido deste dispositivo…
        </main>
      );
    }
    if (offlineVault && (!online || initialAuthUnavailable)) {
      return (
        <OfflineUnlockPage
          metadata={offlineVault}
          canRetryOnline={online}
        />
      );
    }
    return <LoginPage />;
  }

  return (
    <BrowserRouter>
      <StaviaLauncherProvider>
        <Routes>
          <Route path="/home" element={<HomePage />} />
          <Route path="/obras" element={<ObrasPage />} />
          <Route path="/obras/gestao" element={<GestaoObrasRoute />} />
          <Route path="/rdos" element={<RdoWorkspacePage />} />
          <Route path="/tarefas" element={<TarefasPage />} />
          <Route
            path="/mensagens"
            element={
              <Suspense
                fallback={
                  <main className="auth-bootstrap-status" role="status">
                    Abrindo mensagens locais…
                  </main>
                }
              >
                <MensagensPage />
              </Suspense>
            }
          />
          <Route
            path="/seguranca"
            element={
              <CortexShell active={null}>
                <DeviceSecurityPage />
              </CortexShell>
            }
          />
          <Route
            path="/integracoes"
            element={<IntegracoesRoute />}
          />
          <Route
            path="*"
            element={<Navigate to="/home" replace />}
          />
        </Routes>
      </StaviaLauncherProvider>
    </BrowserRouter>
  );
}

export default App;
