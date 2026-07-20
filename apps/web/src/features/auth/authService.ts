import {
  fetchSession,
  loginWithCpf,
  logoutOnline,
} from "./authApi";
import { onlyDigits } from "./loginValidation";
import {
  clearSession,
  purgeLegacyAuthStorage,
  setSession,
  type AuthProfile,
} from "./authSession";

export async function initializeAuthSession(): Promise<AuthProfile | null> {
  purgeLegacyAuthStorage();
  const profile = await fetchSession();
  if (profile) {
    setSession(profile);
  } else {
    clearSession();
  }
  return profile;
}

export async function autenticarPorCpf(cpf: string): Promise<AuthProfile> {
  const profile = await loginWithCpf(onlyDigits(cpf));
  setSession(profile);
  return profile;
}

/** Só limpa a memória depois que o servidor revoga ou confirma expiração. */
export async function encerrarSessao(): Promise<void> {
  await logoutOnline();
  clearSession();
}
