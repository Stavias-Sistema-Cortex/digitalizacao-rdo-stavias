import {
  getSession,
  type AuthProfile,
} from "../../auth/authSession";

export interface MemorySessionGuard {
  readonly session: AuthProfile;
  readonly fingerprint: string;
}

export function captureMemorySessionGuard(): MemorySessionGuard {
  const session = getSession();
  if (!session) {
    throw new Error("A sessão terminou durante a leitura da Memória.");
  }
  return {
    session,
    fingerprint: memorySessionFingerprint(session),
  };
}

export function isMemorySessionGuardCurrent(
  guard: MemorySessionGuard,
): boolean {
  const current = getSession();
  return current !== null &&
    current === guard.session &&
    Date.parse(current.expiraEm) > Date.now() &&
    memorySessionFingerprint(current) === guard.fingerprint;
}

export function assertMemorySessionGuard(
  guard: MemorySessionGuard,
): void {
  if (!isMemorySessionGuardCurrent(guard)) {
    throw new Error("A autorização mudou durante a leitura da Memória.");
  }
}

export function commitIfMemorySessionCurrent(
  guard: MemorySessionGuard,
  commit: () => void,
): boolean {
  if (!isMemorySessionGuardCurrent(guard)) return false;
  commit();
  return true;
}

function memorySessionFingerprint(session: AuthProfile): string {
  return JSON.stringify({
    colaboradorId: session.colaboradorId,
    papelAcesso: session.papelAcesso,
    escopoGlobal: session.escopoGlobal,
    obraIds: [...new Set(session.obraIds)].sort(),
    expiraEm: session.expiraEm,
  });
}
