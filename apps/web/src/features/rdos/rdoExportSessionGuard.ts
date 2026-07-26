import { getSession } from "../auth/authSession";
import {
  canonicalMutationJson,
} from "../../lib/sync/mutationEnvelope";

export const RDO_EXPORT_SESSION_CHANGED_MESSAGE =
  "A sessão mudou durante a exportação local do RDO; o download foi bloqueado.";

export interface RdoExportSessionGuard {
  readonly fingerprint: string;
}

function exportSessionFingerprint(): string | null {
  const session = getSession();
  if (!session) return null;
  return canonicalMutationJson({
    colaboradorId: session.colaboradorId,
    papelAcesso: session.papelAcesso,
    escopoGlobal: session.escopoGlobal,
    obraIds: [...session.obraIds].sort(),
    expiraEm: session.expiraEm,
  });
}

export function captureRdoExportSessionGuard(): RdoExportSessionGuard {
  const fingerprint = exportSessionFingerprint();
  if (!fingerprint) {
    throw new Error(RDO_EXPORT_SESSION_CHANGED_MESSAGE);
  }
  return { fingerprint };
}

export function assertRdoExportSessionGuard(
  expected: RdoExportSessionGuard,
  obraId: string,
): void {
  const session = getSession();
  if (
    !session ||
    exportSessionFingerprint() !== expected.fingerprint ||
    (!session.escopoGlobal && !session.obraIds.includes(obraId))
  ) {
    throw new Error(RDO_EXPORT_SESSION_CHANGED_MESSAGE);
  }
}
