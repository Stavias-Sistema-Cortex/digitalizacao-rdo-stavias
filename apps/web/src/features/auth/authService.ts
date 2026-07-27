import {
  fetchOfflineGrantAfterFreshCpfLogin,
  fetchSession,
  loginWithCpf,
  logoutOnline,
} from "./authApi";
import {
  OfflineGrantOwnerMismatchError,
  saveCollaborativeOfflineGrant,
} from "./collaborativeOfflineGrant";
import { onlyDigits } from "./loginValidation";
import {
  clearSession,
  purgeLegacyAuthStorage,
  setSession,
  type AuthProfile,
} from "./authSession";
import {
  clearRemoteSessionIsolation,
  hasRemoteSessionIsolation,
  markRemoteSessionIsolation,
} from "./remoteSessionIsolation";

export type CpfAuthenticationResult = {
  profile: AuthProfile;
  offlineGrant: "READY" | "UNAVAILABLE";
};

export async function initializeAuthSession(): Promise<AuthProfile | null> {
  purgeLegacyAuthStorage();
  if (hasRemoteSessionIsolation()) {
    clearSession();
    return null;
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
  // Keep every ordinary credentialed request blocked throughout this
  // multi-request transition. A second tab can rotate the shared HttpOnly
  // cookie after /auth/login, so only the signed grant can release it.
  clearSession();
  markRemoteSessionIsolation();
  try {
    const profile = await loginWithCpf(canonicalCpf);
    const signedGrant = await fetchOfflineGrantAfterFreshCpfLogin();
    await saveCollaborativeOfflineGrant(
      canonicalCpf,
      signedGrant,
      profile.colaboradorId,
    );
    clearRemoteSessionIsolation();
    setSession(profile);
    return { profile, offlineGrant: "READY" };
  } catch (error: unknown) {
    markRemoteSessionIsolation();
    clearSession();
    if (error instanceof OfflineGrantOwnerMismatchError) {
      throw new Error(
        "A sessão foi alterada durante a atualização do acesso offline. Entre novamente.",
        { cause: error },
      );
    }
    throw new Error(
      "Não foi possível confirmar a sessão recém-autenticada. Entre novamente.",
      { cause: error },
    );
  }
}

/** Bloqueia o dispositivo antes de tentar revogar a sessão no servidor. */
export async function encerrarSessao(): Promise<void> {
  const remoteAlreadyIsolated = hasRemoteSessionIsolation();
  clearSession();
  if (!remoteAlreadyIsolated) {
    markRemoteSessionIsolation();
  }
  // A persisted marker may have survived a reload after offline access. Its
  // cookie cannot be assumed to belong to this browser state, so never revoke
  // it implicitly.
  if (remoteAlreadyIsolated) {
    return;
  }
  await logoutOnline();
  clearRemoteSessionIsolation();
}
