import { RdoWorkspacePage } from "./features/rdos/RdoWorkspacePage";
import { useAutomaticSync } from "./lib/sync/useAutomaticSync";

function App() {
  useAutomaticSync();

  return <RdoWorkspacePage />;
}

export default App;