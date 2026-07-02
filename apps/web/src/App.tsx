import { useEffect, useState } from "react";

import {
  AUTH_SESSION_CHANGED_EVENT,
  getSession,
} from "./features/auth/authSession";
import { LoginPage } from "./features/auth/LoginPage";
import { RdoWorkspacePage } from "./features/rdos/RdoWorkspacePage";
import { useAutomaticSync } from "./lib/sync/useAutomaticSync";

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

  return <RdoWorkspacePage />;
}

export default App;
