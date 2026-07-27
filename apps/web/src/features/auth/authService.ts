import {
  fetchOfflineGrant,
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
  const profile = await loginWithCpf(canonicalCpf);
  // A successful explicit login replaces the remote cookie. Only then can a
  // prior offline isolation marker be released for the normal grant request.
  clearRemoteSessionIsolation();
  clearSession();
  try {
    const signedGrant = await fetchOfflineGrant();
    await saveCollaborativeOfflineGrant(
      canonicalCpf,
      signedGrant,
      profile.colaboradorId,
    );
    setSession(profile);
    return { profile, offlineGrant: "READY" };
  } catch (error: unknown) {
    if (error instanceof OfflineGrantOwnerMismatchError) {
      markRemoteSessionIsolation();
      clearSession();
      throw new Error(
        "A sessão foi alterada durante a atualização do acesso offline. Entre novamente.",
        { cause: error },
      );
    }
    setSession(profile);
    return { profile, offlineGrant: "UNAVAILABLE" };
  }
}

/** Bloqueia o dispositivo antes de tentar revogar a sessão no servidor. */
export async function encerrarSessao(): Promise<void> {
  const remoteAlreadyIsolated = hasRemoteSessionIsolation();
  if (!remoteAlreadyIsolated) {
    markRemoteSessionIsolation();
  }
  clearSession();
  // A persisted marker may have survived a reload after offline access. Its
  // cookie cannot be assumed to belong to this browser state, so never revoke
  // it implicitly.
  if (remoteAlreadyIsolated) {
    return;
  }
  await logoutOnline();
  clearRemoteSessionIsolation();
}
