const LEGACY_SESSION_KEY = "cortex.auth.sessao";
const LEGACY_FILTER_KEY = "cortex.auth.cpfFilter";
const AUTH_BROADCAST_CHANNEL = "cortex-auth-session-v1";
const LOGOUT_MESSAGE = "LOGOUT";

export const AUTH_SESSION_CHANGED_EVENT =
  "cortex-auth-session-changed";

export type AuthProfile = {
  colaboradorId: string;
  nome: string;
  papelAcesso: "ALFA" | "BETA";
  expiraEm: string;
};

/** Compatibilidade nominal para consumidores; não representa credenciais. */
export type AuthSession = AuthProfile;

let currentSession: AuthProfile | null = null;
let broadcastChannel: BroadcastChannel | null | undefined;

export function isAlfa(session: AuthProfile | null): boolean {
  return session?.papelAcesso === "ALFA";
}

export function getSession(): AuthProfile | null {
  ensureBroadcastChannel();
  return currentSession;
}

export function hasOnlineSession(): boolean {
  return currentSession !== null;
}

export function setSession(session: AuthProfile): void {
  ensureBroadcastChannel();
  currentSession = session;
  dispatchSessionChanged();
}

export function clearSession(): void {
  const channel = ensureBroadcastChannel();
  clearSessionLocally();
  channel?.postMessage(LOGOUT_MESSAGE);
}

function clearSessionLocally(): void {
  currentSession = null;
  dispatchSessionChanged();
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
  if (broadcastChannel !== undefined) {
    return broadcastChannel;
  }
  if (typeof BroadcastChannel === "undefined") {
    broadcastChannel = null;
    return null;
  }

  const channel = new BroadcastChannel(AUTH_BROADCAST_CHANNEL);
  channel.onmessage = (event: MessageEvent<unknown>) => {
    if (event.data === LOGOUT_MESSAGE) {
      clearSessionLocally();
    }
  };
  broadcastChannel = channel;
  return channel;
}
