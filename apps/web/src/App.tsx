import { lazy, Suspense, useEffect, useState } from "react";
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useNavigate,
} from "react-router";

import {
  AUTH_SESSION_CHANGED_EVENT,
  clearSessionForCurrentDocument,
  getSession,
  isAlfa,
} from "./features/auth/authSession";
import {
  AUTH_NOTICE_CHANGED_EVENT,
  consumeAuthNotice,
} from "./features/auth/authNotice";
import { LoginPage } from "./features/auth/LoginPage";
import { DeviceSecurityPage } from "./features/auth/DeviceSecurityPage";
import { OfflineUnlockPage } from "./features/auth/OfflineUnlockPage";
import {
  hasCollaborativeOfflineGrantMetadata,
  loadOfflineVaultMetadata,
} from "./features/auth/offlineVaultRepository";
import type { OfflineVaultMetadata } from "./features/auth/offlineVault.types";
import { CortexShell } from "./components/shell/CortexShell";
import { useAppAutomaticSync } from "./appAutomaticSync";
import { initializeCortexDb } from "./lib/db/cortexDb";

const HomePage = lazy(() =>
  import("./features/home/HomePage").then((module) => ({
    default: module.HomePage,
  })),
);

const ObrasPage = lazy(() =>
  import("./features/obras/ObrasPage").then((module) => ({
    default: module.ObrasPage,
  })),
);

const GestaoObrasPage = lazy(() =>
  import("./features/obras/gestao/GestaoObrasPage").then((module) => ({
    default: module.GestaoObrasPage,
  })),
);

const RdoWorkspacePage = lazy(() =>
  import("./features/rdos/RdoWorkspacePage").then((module) => ({
    default: module.RdoWorkspacePage,
  })),
);

const TarefasPage = lazy(() =>
  import("./features/tarefas/TarefasPage").then((module) => ({
    default: module.TarefasPage,
  })),
);

const EquipesPage = lazy(() =>
  import("./features/equipes/EquipesPage").then((module) => ({
    default: module.EquipesPage,
  })),
);

const IntegracoesPage = lazy(() =>
  import("./features/integracoes/IntegracoesPage").then((module) => ({
    default: module.IntegracoesPage,
  })),
);

const MensagensPage = lazy(() =>
  import("./features/mensagens/MensagensPage").then((module) => ({
    default: module.MensagensPage,
  })),
);

const FinanceiroPage = lazy(() =>
  import("./features/financeiro/FinanceiroPage").then((module) => ({
    default: module.FinanceiroPage,
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
  const sessionScope = session === null
    ? null
    : [
      session.colaboradorId,
      session.papelAcesso,
      session.escopoGlobal ? "global" : session.obraIds.join(","),
      session.expiraEm,
    ].join("\u0000");
  const [preparedSessionScope, setPreparedSessionScope] =
    useState<string | null>(null);
  const [online, setOnline] = useState(() => navigator.onLine);
  const [offlineVault, setOfflineVault] =
    useState<OfflineVaultMetadata | null>(null);
  const [hasCollaborativeCpfGrant, setHasCollaborativeCpfGrant] =
    useState(false);
  const [vaultChecked, setVaultChecked] = useState(false);
  const [authNotice, setAuthNotice] = useState(() =>
    session ? consumeAuthNotice() : null,
  );
  const localDataReady = sessionScope !== null &&
    preparedSessionScope === sessionScope;

  useAppAutomaticSync(localDataReady ? session : null);

  useEffect(() => {
    function refreshSession() {
      setSession(getSession());
    }

    function refreshAuthNotice() {
      const nextNotice = consumeAuthNotice();
      if (nextNotice) {
        setAuthNotice(nextNotice);
      }
    }

    window.addEventListener(
      AUTH_SESSION_CHANGED_EVENT,
      refreshSession,
    );
    window.addEventListener(
      AUTH_NOTICE_CHANGED_EVENT,
      refreshAuthNotice,
    );
    return () => {
      window.removeEventListener(
        AUTH_SESSION_CHANGED_EVENT,
        refreshSession,
      );
      window.removeEventListener(
        AUTH_NOTICE_CHANGED_EVENT,
        refreshAuthNotice,
      );
    };
  }, []);

  useEffect(() => {
    if (sessionScope === null) {
      return;
    }
    let active = true;
    void initializeCortexDb()
      .then(() => {
        if (active) {
          setPreparedSessionScope(sessionScope);
        }
      })
      .catch(() => {
        if (active) {
          clearSessionForCurrentDocument();
        }
      });
    return () => {
      active = false;
    };
  }, [sessionScope]);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      loadOfflineVaultMetadata().catch(() => null),
      hasCollaborativeOfflineGrantMetadata().catch(() => false),
    ])
      .then(([metadata, hasCpfGrant]) => {
        if (!cancelled) {
          setOfflineVault(metadata);
          setHasCollaborativeCpfGrant(hasCpfGrant);
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

  // Sem sessão online, somente um grant assinado validado libera o cache.
  if (!session) {
    if (!vaultChecked) {
      return (
        <main className="auth-bootstrap-status" role="status">
          Verificando o acesso protegido deste dispositivo…
        </main>
      );
    }
    if (
      (offlineVault || hasCollaborativeCpfGrant) &&
      (!online || initialAuthUnavailable)
    ) {
      return (
        <OfflineUnlockPage
          passkeyMetadata={offlineVault}
          hasCollaborativeCpfGrant={hasCollaborativeCpfGrant}
          canRetryOnline={online}
        />
      );
    }
    return <LoginPage />;
  }

  if (!localDataReady) {
    return (
      <main className="auth-bootstrap-status" role="status">
        Preparando os dados locais protegidos…
      </main>
    );
  }

  return (
    <BrowserRouter>
      {authNotice ? (
        <aside
          className="auth-session-notice"
          role="status"
          aria-live="polite"
          aria-atomic="true"
        >
          {authNotice}
        </aside>
      ) : null}
      <Suspense
        fallback={
          <main className="auth-bootstrap-status" role="status">
            Abrindo módulo…
          </main>
        }
      >
        <Routes>
          <Route path="/home" element={<HomePage />} />
          <Route path="/obras" element={<ObrasPage />} />
          <Route path="/obras/gestao" element={<GestaoObrasRoute />} />
          <Route path="/rdos" element={<RdoWorkspacePage />} />
          <Route path="/tarefas" element={<TarefasPage />} />
          <Route path="/equipes" element={<EquipesPage />} />
          <Route path="/financeiro" element={<FinanceiroPage />} />
          <Route path="/mensagens" element={<MensagensPage />} />
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
      </Suspense>
    </BrowserRouter>
  );
}

export default App;
