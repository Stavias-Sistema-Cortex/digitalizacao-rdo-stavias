import { LIMITE_DE_OBRAS_NO_ESCOPO } from "./escopoDeObras";

const LEGACY_SESSION_KEY = "cortex.auth.sessao";
const LEGACY_FILTER_KEY = "cortex.auth.cpfFilter";
const AUTH_BROADCAST_CHANNEL = "cortex-auth-session-v1";
const LOGOUT_MESSAGE = "LOGOUT";
const SESSION_REPLACED_MESSAGE = "SESSION_REPLACED";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export const AUTH_SESSION_CHANGED_EVENT =
  "cortex-auth-session-changed";

export type AuthProfile = {
  colaboradorId: string;
  nome: string;
  papelAcesso: "ALFA" | "BETA";
  escopoGlobal: boolean;
  obraIds: string[];
  expiraEm: string;
};

/** Compatibilidade nominal para consumidores; não representa credenciais. */
export type AuthSession = AuthProfile;

let onlineSession: AuthProfile | null = null;
let offlineSession: AuthProfile | null = null;
let broadcastChannel: BroadcastChannel | null | undefined;
let expiryTimer: ReturnType<typeof setTimeout> | null = null;
let lifecycleListenersRegistered = false;

export function isAlfa(session: AuthProfile | null): boolean {
  return session?.papelAcesso === "ALFA";
}

export function getSession(): AuthProfile | null {
  ensureBroadcastChannel();
  expireInvalidSessions();
  return onlineSession ?? offlineSession;
}

export function hasOnlineSession(): boolean {
  expireInvalidSessions();
  return onlineSession !== null;
}

export function hasOfflineSession(): boolean {
  expireInvalidSessions();
  return onlineSession === null && offlineSession !== null;
}

export function setSession(session: AuthProfile): void {
  const channel = ensureBroadcastChannel();
  onlineSession = canonicalProfile(session);
  offlineSession = null;
  scheduleExpiry();
  dispatchSessionChanged();
  // The opaque auth cookie is origin-wide while its proof is tab-bound.
  // A fresh login in this document therefore replaces the cookie seen by
  // every older document. Retire those stale in-memory profiles immediately
  // instead of leaving an editor open until its next request fails.
  channel?.postMessage(SESSION_REPLACED_MESSAGE);
}

export function setOfflineSession(session: AuthProfile): void {
  ensureBroadcastChannel();
  onlineSession = null;
  offlineSession = canonicalProfile(session);
  scheduleExpiry();
  dispatchSessionChanged();
}

export function clearOfflineSession(): void {
  offlineSession = null;
  scheduleExpiry();
  dispatchSessionChanged();
}

export function clearSession(): void {
  const channel = ensureBroadcastChannel();
  clearSessionLocally();
  channel?.postMessage(LOGOUT_MESSAGE);
}

/** Clears only this document; explicit logout remains the cross-tab action. */
export function clearSessionForCurrentDocument(): void {
  clearSessionLocally();
}

function clearSessionLocally(): void {
  onlineSession = null;
  offlineSession = null;
  clearRetiredPrivateLocalStorage();
  scheduleExpiry();
  dispatchSessionChanged();
}

function clearRetiredPrivateLocalStorage(): void {
  const LEGACY_PRIVATE_LOCAL_STORAGE_KEYS = [
    "cortex:stavia:chat:operacional",
    "cortex:stavia:last-context",
  ] as const;
  try {
    const target = typeof window === "undefined" ? null : window.localStorage;
    if (!target) {
      return;
    }
    for (const key of LEGACY_PRIVATE_LOCAL_STORAGE_KEYS) {
      target.removeItem(key);
    }
  } catch {
    // Session memory is already cleared. Storage privacy failures must not
    // prevent higher-level remote-session isolation from taking effect.
  }
}

export function requireDataScope(): {
  ownerId: string;
  scopeMaterial: string;
} {
  const session = getSession();
  if (!session) {
    throw new Error("Sessão válida obrigatória para acessar dados locais.");
  }
  return {
    ownerId: session.colaboradorId,
    scopeMaterial: session.escopoGlobal
      ? "ALFA:GLOBAL"
      : `BETA:${session.obraIds.join(",")}`,
  };
}

/** Remove material legado sem tentar desserializar CPF, JWT ou Bloom filter. */
export function purgeLegacyAuthStorage(): void {
  if (typeof localStorage === "undefined") {
    return;
  }
  try {
    localStorage.removeItem(LEGACY_SESSION_KEY);
    localStorage.removeItem(LEGACY_FILTER_KEY);
  } catch {
    // Storage pode estar indisponível; a sessão atual continua só em memória.
  }
}

function dispatchSessionChanged(): void {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT));
  }
}

function ensureBroadcastChannel(): BroadcastChannel | null {
  ensureLifecycleListeners();
  if (broadcastChannel !== undefined) {
    return broadcastChannel;
  }
  if (typeof BroadcastChannel === "undefined") {
    broadcastChannel = null;
    return null;
  }

  const channel = new BroadcastChannel(AUTH_BROADCAST_CHANNEL);
  channel.onmessage = (event: MessageEvent<unknown>) => {
    if (
      event.data === LOGOUT_MESSAGE ||
      event.data === SESSION_REPLACED_MESSAGE
    ) {
      clearSessionLocally();
    }
  };
  broadcastChannel = channel;
  return channel;
}

function canonicalProfile(session: AuthProfile): AuthProfile {
  const expiresAt = Date.parse(session.expiraEm);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    throw new Error("Sessão inválida ou expirada.");
  }
  if (
    !UUID_PATTERN.test(session.colaboradorId) ||
    session.obraIds.length > LIMITE_DE_OBRAS_NO_ESCOPO
  ) {
    throw new Error("Escopo da sessão inválido.");
  }
  const obraIds = [...new Set(session.obraIds)].sort();
  if (
    obraIds.some((id) => !UUID_PATTERN.test(id)) ||
    session.escopoGlobal !== (session.papelAcesso === "ALFA") ||
    (session.escopoGlobal && obraIds.length > 0)
  ) {
    throw new Error("Escopo da sessão inválido.");
  }
  return { ...session, obraIds };
}

function expireInvalidSessions(): void {
  const now = Date.now();
  const onlineExpired = onlineSession !== null &&
    Date.parse(onlineSession.expiraEm) <= now;
  const offlineExpired = offlineSession !== null &&
    Date.parse(offlineSession.expiraEm) <= now;
  if (!onlineExpired && !offlineExpired) {
    return;
  }
  if (onlineExpired) {
    onlineSession = null;
  }
  if (offlineExpired) {
    offlineSession = null;
  }
  scheduleExpiry();
  dispatchSessionChanged();
}

function scheduleExpiry(): void {
  if (expiryTimer !== null) {
    clearTimeout(expiryTimer);
    expiryTimer = null;
  }
  const expirations = [onlineSession, offlineSession]
    .filter((session): session is AuthProfile => session !== null)
    .map((session) => Date.parse(session.expiraEm));
  if (expirations.length === 0) {
    return;
  }
  const delay = Math.max(
    0,
    Math.min(...expirations) - Date.now(),
  );
  expiryTimer = setTimeout(
    expireInvalidSessions,
    Math.min(delay + 1, 2_147_483_647),
  );
}

function ensureLifecycleListeners(): void {
  if (
    lifecycleListenersRegistered ||
    typeof window === "undefined" ||
    typeof window.addEventListener !== "function"
  ) {
    return;
  }
  lifecycleListenersRegistered = true;
  window.addEventListener("pageshow", expireInvalidSessions);
  if (typeof document !== "undefined") {
    document.addEventListener("visibilitychange", expireInvalidSessions);
  }
}
