const SESSION_KEY = "cortex.auth.sessao";
export const AUTH_SESSION_CHANGED_EVENT =
  "cortex-auth-session-changed";

export type AuthSession = {
  colaboradorId: string | null;
  nome: string | null;
  cpfMascarado: string;
  /** CPF (somente dígitos) do próprio usuário, para renovar o token. */
  cpf: string | null;
  perfil: string | null;
  /** Papel de acesso: "ALFA" (global) ou "BETA" (operacional). */
  papelAcesso: string | null;
  /** JWT emitido pelo servidor; ausente em sessão criada offline. */
  token: string | null;
  origem: "online" | "offline";
  autenticadoEm: string;
};

/**
 * Papel Alfa = acesso administrativo global. A decisão de segurança real é
 * sempre do backend; isto apenas ajusta o que a interface mostra.
 */
export function isAlfa(session: AuthSession | null): boolean {
  return (session?.papelAcesso ?? "").toUpperCase() === "ALFA";
}

export function getSession(): AuthSession | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) {
      return null;
    }

    const parsed = JSON.parse(raw) as AuthSession;
    if (!parsed || typeof parsed.cpfMascarado !== "string") {
      return null;
    }

    return parsed;
  } catch {
    return null;
  }
}

export function setSession(session: AuthSession): void {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  window.dispatchEvent(
    new Event(AUTH_SESSION_CHANGED_EVENT),
  );
}

export function clearSession(): void {
  localStorage.removeItem(SESSION_KEY);
  window.dispatchEvent(
    new Event(AUTH_SESSION_CHANGED_EVENT),
  );
}
