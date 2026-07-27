const AUTH_NOTICE_KEY = "cortex.auth.notice";
const OFFLINE_GRANT_UNAVAILABLE = "offline-grant-unavailable";

export const OFFLINE_GRANT_UNAVAILABLE_MESSAGE =
  "Acesso realizado. O acesso offline deste dispositivo não foi atualizado.";

/** Persiste somente um marcador genérico durante a navegação da sessão. */
export function queueOfflineGrantUnavailableNotice(): void {
  try {
    sessionStorage.setItem(AUTH_NOTICE_KEY, OFFLINE_GRANT_UNAVAILABLE);
  } catch {
    // O acesso online continua válido quando o storage do navegador falha.
  }
}

/** Consome o aviso uma única vez, sem expor dados de sessão. */
export function consumeAuthNotice(): string | null {
  try {
    const notice = sessionStorage.getItem(AUTH_NOTICE_KEY);
    sessionStorage.removeItem(AUTH_NOTICE_KEY);
    return notice === OFFLINE_GRANT_UNAVAILABLE
      ? OFFLINE_GRANT_UNAVAILABLE_MESSAGE
      : null;
  } catch {
    return null;
  }
}
