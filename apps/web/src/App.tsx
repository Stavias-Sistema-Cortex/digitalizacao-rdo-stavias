import { getSession } from "./features/auth/authSession";
import { LoginPage } from "./features/auth/LoginPage";
import { RdoWorkspacePage } from "./features/rdos/RdoWorkspacePage";
import { useAutomaticSync } from "./lib/sync/useAutomaticSync";

function App() {
  useAutomaticSync();

  // Sem sessão local o acesso é bloqueado: exibe o login.
  if (!getSession()) {
    return <LoginPage />;
  }

  return <RdoWorkspacePage />;
}

export default App;