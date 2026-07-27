import {
  fetchOfflineGrantAfterFreshCpfLogin,
  fetchSession,
  loginWithCpf,
  logoutOnline,
} from "./authApi";
import {
  ApiError,
  ApiTransportError,
} from "../../lib/api/apiClient";
import {
  OfflineGrantOwnerMismatchError,
  saveCollaborativeOfflineGrant,
} from "./collaborativeOfflineGrant";
import { onlyDigits } from "./loginValidation";
import {
  clearSession,
  clearSessionForCurrentDocument,
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

export class LogoutReauthenticationRequiredError extends Error {
  constructor(cause: unknown) {
    super(
      "Esta aba não pode confirmar a sessão remota atual. Entre novamente antes de encerrar a sessão compartilhada.",
      { cause },
    );
    this.name = "LogoutReauthenticationRequiredError";
  }
}

export async function initializeAuthSession(): Promise<AuthProfile | null> {
  purgeLegacyAuthStorage();
  if (hasRemoteSessionIsolation()) {
    clearSessionForCurrentDocument();
    return null;
  }
  const profile = await fetchSession();
  if (profile) {
    setSession(profile);
  } else {
    clearSessionForCurrentDocument();
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
  clearSessionForCurrentDocument();
  markRemoteSessionIsolation();
  let profile: AuthProfile;
  try {
    profile = await loginWithCpf(canonicalCpf);
  } catch (error: unknown) {
    failClosedAfterFreshLogin(error);
  }
  try {
    const signedGrant = await fetchOfflineGrantAfterFreshCpfLogin();
    await saveCollaborativeOfflineGrant(
      canonicalCpf,
      signedGrant,
      profile.colaboradorId,
    );
  } catch (error: unknown) {
    if (requiresRemoteSessionIsolation(error)) {
      failClosedAfterFreshLogin(error);
    }
    // The session cookie is now cryptographically bound to this tab. A
    // transient grant transport/cache failure must not discard a valid online
    // login; it only leaves collaborative offline access unavailable.
    try {
      clearRemoteSessionIsolation();
      setSession(profile);
      return { profile, offlineGrant: "UNAVAILABLE" };
    } catch (releaseError: unknown) {
      failClosedAfterFreshLogin(releaseError);
    }
  }
  try {
    clearRemoteSessionIsolation();
    setSession(profile);
    return { profile, offlineGrant: "READY" };
  } catch (error: unknown) {
    failClosedAfterFreshLogin(error);
  }
}

/** Bloqueia o dispositivo antes de tentar revogar a sessão no servidor. */
export async function encerrarSessao(): Promise<void> {
  const remoteAlreadyIsolated = hasRemoteSessionIsolation();
  // Until the server confirms revocation, clear only this document: another
  // tab may have replaced the shared cookie with its own bound session.
  clearSessionForCurrentDocument();
  if (!remoteAlreadyIsolated) {
    markRemoteSessionIsolation();
  }
  // A persisted marker may have survived a reload after offline access. Its
  // cookie cannot be assumed to belong to this browser state, so never revoke
  // it implicitly.
  if (remoteAlreadyIsolated) {
    return;
  }
  try {
    await logoutOnline();
  } catch (error: unknown) {
    if (error instanceof ApiError && error.status === 401) {
      throw new LogoutReauthenticationRequiredError(error);
    }
    throw error;
  }
  // A confirmed server revoke is the only logout outcome that propagates to
  // every same-origin document and releases the origin-wide isolation marker.
  clearSession();
  clearRemoteSessionIsolation();
}

function requiresRemoteSessionIsolation(error: unknown): boolean {
  return error instanceof OfflineGrantOwnerMismatchError ||
    (error instanceof ApiError &&
      (error.status === 401 || error.status === 403)) ||
    (error instanceof ApiTransportError &&
      error.kind === "REMOTE_SESSION_ISOLATED");
}

function failClosedAfterFreshLogin(error: unknown): never {
  try {
    markRemoteSessionIsolation();
  } catch {
    // The initial pre-login marker already failed closed in memory. Do not
    // replace the authentication error with a storage implementation detail.
  }
  clearSessionForCurrentDocument();
  if (error instanceof OfflineGrantOwnerMismatchError) {
    throw new Error(
      "A sessão foi alterada durante a atualização do acesso offline. Entre novamente.",
      { cause: error },
    );
  }
  if (error instanceof ApiError || error instanceof ApiTransportError) {
    throw error;
  }
  throw new Error(
    "Não foi possível confirmar a sessão recém-autenticada. Entre novamente.",
    { cause: error },
  );
}
