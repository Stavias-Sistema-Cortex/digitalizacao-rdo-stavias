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
  getSession,
} from "./features/auth/authSession";
import { LoginPage } from "./features/auth/LoginPage";
import { CortexShell } from "./components/shell/CortexShell";
import { HomePage } from "./features/home/HomePage";
import { IntegracoesPage } from "./features/integracoes/IntegracoesPage";
import { ObrasPage } from "./features/obras/ObrasPage";
import { RdoWorkspacePage } from "./features/rdos/RdoWorkspacePage";
import { useAutomaticSync } from "./lib/sync/useAutomaticSync";

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

function App() {
  const [session, setSession] =
    useState(() => getSession());

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

  // Sem sessão local o acesso é bloqueado: exibe o login.
  if (!session) {
    return <LoginPage />;
  }

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/home" element={<HomePage />} />
        <Route path="/obras" element={<ObrasPage />} />
        <Route path="/rdos" element={<RdoWorkspacePage />} />
        <Route
          path="/integracoes"
          element={<IntegracoesRoute />}
        />
        <Route
          path="*"
          element={<Navigate to="/home" replace />}
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
