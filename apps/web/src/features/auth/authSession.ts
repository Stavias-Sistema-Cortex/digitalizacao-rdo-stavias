const SESSION_KEY = "cortex.auth.sessao";

export type AuthSession = {
  colaboradorId: string | null;
  nome: string | null;
  cpfMascarado: string;
  /** CPF (somente dígitos) do próprio usuário, para renovar o token. */
  cpf: string | null;
  perfil: string | null;
  /** JWT emitido pelo servidor; ausente em sessão criada offline. */
  token: string | null;
  origem: "online" | "offline";
  autenticadoEm: string;
};

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
}

export function clearSession(): void {
  localStorage.removeItem(SESSION_KEY);
}
