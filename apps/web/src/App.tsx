import { useEffect, useState } from "react";
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useNavigate,
} from "react-router-dom";

import {
  AUTH_SESSION_CHANGED_EVENT,
  clearSession,
  getSession,
  isAlfa,
} from "./features/auth/authSession";
import { LoginPage } from "./features/auth/LoginPage";
import { CortexShell } from "./components/shell/CortexShell";
import { HomePage } from "./features/home/HomePage";
import { IntegracoesPage } from "./features/integracoes/IntegracoesPage";
import { EquipesPage } from "./features/equipes/EquipesPage";
import { MensagensPage } from "./features/mensagens/MensagensPage";
import { ObrasPage } from "./features/obras/ObrasPage";
import { GestaoObrasPage } from "./features/obras/gestao/GestaoObrasPage";
import { RdoWorkspacePage } from "./features/rdos/RdoWorkspacePage";
import { StaviaLauncherProvider } from "./features/stavia/StaviaLauncherProvider";
import { TarefasPage } from "./features/tarefas/TarefasPage";
import { useAutomaticSync } from "./lib/sync/useAutomaticSync";
import { bindLocalDataToUser } from "./lib/db/localDataScope";

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

function App() {
  const [session, setSession] =
    useState(() => getSession());
  const [localScopeUserId, setLocalScopeUserId] = useState<string | null>(null);

  useAutomaticSync();

  useEffect(() => {
    function refreshSession() {
      setSession(getSession());
    }

    window.addEventListener(
      AUTH_SESSION_CHANGED_EVENT,
      refreshSession,
    );
    window.addEventListener(
      "storage",
      refreshSession,
    );

    return () => {
      window.removeEventListener(
        AUTH_SESSION_CHANGED_EVENT,
        refreshSession,
      );
      window.removeEventListener(
        "storage",
        refreshSession,
      );
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function bindSessionScope() {
      if (!session) {
        return;
      }
      if (!session.colaboradorId) {
        clearSession();
        return;
      }
      await bindLocalDataToUser(session.colaboradorId);
      if (!cancelled) setLocalScopeUserId(session.colaboradorId);
    }
    void bindSessionScope();
    return () => {
      cancelled = true;
    };
  }, [session]);

  // Sem sessão local o acesso é bloqueado: exibe o login.
  if (!session) {
    return <LoginPage />;
  }

  if (localScopeUserId !== session.colaboradorId) {
    return <main className="app-scope-loading">Preparando seus dados locais…</main>;
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
          <Route path="/equipes" element={<EquipesPage />} />
          <Route path="/mensagens" element={<MensagensPage />} />
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
