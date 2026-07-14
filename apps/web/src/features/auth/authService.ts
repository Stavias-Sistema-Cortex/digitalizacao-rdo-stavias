import {
  fetchSession,
  logoutOnline,
  requestEmailCode,
  verifyEmailCode,
  type OtpChallenge,
} from "./authApi";
import { onlyDigits } from "./loginValidation";
import {
  clearSession,
  purgeLegacyAuthStorage,
  setSession,
  type AuthProfile,
} from "./authSession";

export async function initializeAuthSession(): Promise<AuthProfile | null> {
  purgeLegacyAuthStorage();
  const profile = await fetchSession();
  if (profile) {
    setSession(profile);
  } else {
    clearSession();
  }
  return profile;
}

export function solicitarCodigo(identifier: string): Promise<OtpChallenge> {
  return requestEmailCode(onlyDigits(identifier));
}

export async function verificarCodigo(
  challengeId: string,
  code: string,
): Promise<AuthProfile> {
  const profile = await verifyEmailCode(challengeId, onlyDigits(code));
  setSession(profile);
  return profile;
}

/** Só limpa a memória depois que o servidor revoga ou confirma expiração. */
export async function encerrarSessao(): Promise<void> {
  await logoutOnline();
  clearSession();
}
