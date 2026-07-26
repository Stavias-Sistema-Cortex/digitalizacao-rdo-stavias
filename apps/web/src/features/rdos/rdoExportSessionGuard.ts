import { getSession } from "../auth/authSession";
import { syncSessionFingerprint } from "../../lib/sync/syncSession";

export const RDO_EXPORT_SESSION_CHANGED_MESSAGE =
  "A sessão mudou durante a exportação local do RDO; o download foi bloqueado.";

export interface RdoExportSessionGuard {
  readonly fingerprint: string;
}

export function captureRdoExportSessionGuard(): RdoExportSessionGuard {
  const session = getSession();
  if (!session) {
    throw new Error(RDO_EXPORT_SESSION_CHANGED_MESSAGE);
  }
  return { fingerprint: syncSessionFingerprint(session) };
}

export function assertRdoExportSessionGuard(
  expected: RdoExportSessionGuard,
  obraId: string,
): void {
  const session = getSession();
  if (
    !session ||
    syncSessionFingerprint(session) !== expected.fingerprint ||
    (!session.escopoGlobal && !session.obraIds.includes(obraId))
  ) {
    throw new Error(RDO_EXPORT_SESSION_CHANGED_MESSAGE);
  }
}
