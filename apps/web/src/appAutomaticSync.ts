import {
  hasOnlineSession,
  type AuthProfile,
} from "./features/auth/authSession";
import { useAutomaticSync } from "./lib/sync/useAutomaticSync";

export function useAppAutomaticSync(
  session: AuthProfile | null,
): void {
  useAutomaticSync(session !== null && hasOnlineSession());
}
