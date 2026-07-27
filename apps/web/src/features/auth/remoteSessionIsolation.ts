const REMOTE_SESSION_ISOLATION_KEY = "cortex.auth.remote-session-isolation";
const STORAGE_UNAVAILABLE_MESSAGE =
  "Não foi possível isolar a sessão remota deste dispositivo.";
let transientIsolation = false;

/**
 * This marker deliberately carries no principal, CPF, credential, or grant
 * data. Once offline access has been activated, an HttpOnly cookie can no
 * longer be treated as proof that it belongs to the local browser state.
 */
export function hasRemoteSessionIsolation(): boolean {
  if (transientIsolation) {
    return true;
  }
  if (typeof localStorage === "undefined") {
    return false;
  }
  try {
    // Any persisted value is fail-closed. A malformed marker must not make a
    // stale cookie trusted again after a reload.
    return localStorage.getItem(REMOTE_SESSION_ISOLATION_KEY) !== null;
  } catch {
    // In a browser where persistent storage is unavailable, do not allow a
    // credentialed operational request to rehydrate an unknown remote cookie.
    return true;
  }
}

export function markRemoteSessionIsolation(): void {
  try {
    requiredStorage().setItem(REMOTE_SESSION_ISOLATION_KEY, "1");
    transientIsolation = false;
  } catch {
    // A failed durable write still has to isolate this live tab. It can only
    // be released later by a successful explicit clear after fresh auth.
    transientIsolation = true;
    throw new Error(STORAGE_UNAVAILABLE_MESSAGE);
  }
}

/** Only a successful fresh authentication or a known server revoke may clear it. */
export function clearRemoteSessionIsolation(): void {
  try {
    requiredStorage().removeItem(REMOTE_SESSION_ISOLATION_KEY);
    transientIsolation = false;
  } catch {
    transientIsolation = true;
    throw new Error(STORAGE_UNAVAILABLE_MESSAGE);
  }
}

function requiredStorage(): Storage {
  if (typeof localStorage === "undefined") {
    throw new Error(STORAGE_UNAVAILABLE_MESSAGE);
  }
  return localStorage;
}
