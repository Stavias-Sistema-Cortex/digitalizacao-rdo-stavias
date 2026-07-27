import {
  fetchOfflineGrant,
  fetchSession,
  loginWithCpf,
  logoutOnline,
} from "./authApi";
import { saveCollaborativeOfflineGrant } from "./collaborativeOfflineGrant";
import { onlyDigits } from "./loginValidation";
import {
  clearSession,
  purgeLegacyAuthStorage,
  setSession,
  type AuthProfile,
} from "./authSession";

const LOGOUT_PENDING_KEY = "cortex.auth.logoutPending";

export type CpfAuthenticationResult = {
  profile: AuthProfile;
  offlineGrant: "READY" | "UNAVAILABLE";
};

function logoutPending(): boolean {
  try {
    return localStorage.getItem(LOGOUT_PENDING_KEY) === "1";
  } catch {
    return false;
  }
}

function setLogoutPending(value: boolean): void {
  try {
    if (value) localStorage.setItem(LOGOUT_PENDING_KEY, "1");
    else localStorage.removeItem(LOGOUT_PENDING_KEY);
  } catch {
    // A sessão em memória ainda é bloqueada quando storage está indisponível.
  }
}

export async function initializeAuthSession(): Promise<AuthProfile | null> {
  purgeLegacyAuthStorage();
  if (logoutPending()) {
    clearSession();
    try {
      await logoutOnline();
      setLogoutPending(false);
    } catch {
      return null;
    }
  }
  const profile = await fetchSession();
  if (profile) {
    setSession(profile);
  } else {
    clearSession();
  }
  return profile;
}

export async function autenticarPorCpf(
  cpf: string,
): Promise<CpfAuthenticationResult> {
  const canonicalCpf = onlyDigits(cpf);
  const profile = await loginWithCpf(canonicalCpf);
  setSession(profile);
  try {
    const signedGrant = await fetchOfflineGrant();
    await saveCollaborativeOfflineGrant(canonicalCpf, signedGrant);
    return { profile, offlineGrant: "READY" };
  } catch {
    return { profile, offlineGrant: "UNAVAILABLE" };
  }
}

/** Bloqueia o dispositivo antes de tentar revogar a sessão no servidor. */
export async function encerrarSessao(): Promise<void> {
  setLogoutPending(true);
  clearSession();
  await logoutOnline();
  setLogoutPending(false);
}
