import { loginOnline } from "./authApi";
import { onlyDigits } from "./loginValidation";
import { setSession, type AuthSession } from "./authSession";

export type AuthOutcome =
  | { ok: true; session: AuthSession }
  | { ok: false; message: string };

function mascararCpf(cpfDigits: string): string {
  return cpfDigits.length === 11
    ? `***.***.***-${cpfDigits.slice(9)}`
    : cpfDigits;
}

/**
 * Mantém o contrato da tela enquanto o login verificável é implementado.
 * Nenhuma falha ou resposta não-sucesso cria uma sessão local.
 */
export async function autenticar(cpf: string): Promise<AuthOutcome> {
  const cpfDigits = onlyDigits(cpf);

  try {
    const online = await loginOnline(cpfDigits);

    if (!online.ok) {
      return {
        ok: false,
        message: online.message,
      };
    }

    const session: AuthSession = {
      colaboradorId: online.profile.colaboradorId,
      nome: online.profile.nome,
      cpfMascarado: online.profile.cpfMascarado ?? mascararCpf(cpfDigits),
      cpf: cpfDigits,
      perfil: online.profile.perfil,
      papelAcesso: online.profile.papelAcesso,
      token: online.profile.token,
      origem: "online",
      autenticadoEm: new Date().toISOString(),
    };
    setSession(session);
    return { ok: true, session };
  } catch {
    return {
      ok: false,
      message:
        "Não foi possível autenticar agora. Verifique a conexão e tente novamente.",
    };
  }
}

/** Compatibilidade temporária: filtros Bloom não são mais distribuídos. */
export async function sincronizarFiltroOffline(): Promise<void> {
  return Promise.resolve();
}
