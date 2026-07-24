import {
  getSession,
  hasOnlineSession,
  type AuthProfile,
} from "../../features/auth/authSession";
import { canonicalMutationJson } from "./mutationEnvelope";

export interface SyncSessionGuard {
  readonly fingerprint: string;
  readonly userId: string;
}

export function captureOnlineSyncSession(): SyncSessionGuard {
  const session = getSession();
  if (!session || !hasOnlineSession()) {
    throw new Error("Faça login novamente para sincronizar com o servidor.");
  }
  return {
    fingerprint: syncSessionFingerprint(session),
    userId: session.colaboradorId,
  };
}

export function assertSyncSession(guard: SyncSessionGuard): void {
  const current = captureOnlineSyncSession();
  if (current.fingerprint !== guard.fingerprint) {
    throw new Error("A sessão mudou durante a sincronização.");
  }
}

export function syncSessionFingerprint(session: AuthProfile): string {
  return canonicalMutationJson({
    colaboradorId: session.colaboradorId,
    nome: session.nome,
    papelAcesso: session.papelAcesso,
    escopoGlobal: session.escopoGlobal,
    obraIds: [...session.obraIds].sort(),
    expiraEm: session.expiraEm,
  });
}
