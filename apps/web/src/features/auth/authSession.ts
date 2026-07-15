const SESSION_KEY = "cortex.auth.sessao";
export const AUTH_SESSION_CHANGED_EVENT =
  "cortex-auth-session-changed";

export type AuthSession = {
  colaboradorId: string | null;
  nome: string | null;
  cpfMascarado: string;
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

    const parsed = JSON.parse(raw) as AuthSession & { cpf?: unknown };
    if (!parsed || typeof parsed.cpfMascarado !== "string") {
      return null;
    }

    // Sessões antigas podem conter o CPF usado para uma renovação silenciosa.
    // A credencial não é necessária para manter a sessão e nunca volta a ser
    // exposta ao restante do app.
    const { cpf: _legacyCpf, ...session } = parsed;
    if (Object.hasOwn(parsed, "cpf")) {
      localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    }
    return session;
  } catch {
    return null;
  }
}

export function setSession(session: AuthSession): void {
  const { cpf: _legacyCpf, ...safeSession } = session as AuthSession & {
    cpf?: unknown;
  };
  localStorage.setItem(SESSION_KEY, JSON.stringify(safeSession));
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
