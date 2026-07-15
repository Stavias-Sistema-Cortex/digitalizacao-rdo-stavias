import { fetchCpfFilter, loginOnline } from "./authApi";
import {
  cpfPodeEstarNoFiltro,
  getCachedFilter,
  setCachedFilter,
} from "./cpfFilter";
import { onlyDigits } from "./loginValidation";
import { setSession, type AuthSession } from "./authSession";
import { bindLocalDataToUser } from "../../lib/db/localDataScope";
import {
  cacheOfflineProfile,
  getCachedOfflineProfile,
} from "./offlineProfileCache";

export type AuthOutcome =
  | { ok: true; session: AuthSession }
  | { ok: false; message: string };

function mascararCpf(cpfDigits: string): string {
  return cpfDigits.length === 11
    ? `***.***.***-${cpfDigits.slice(9)}`
    : cpfDigits;
}

/**
 * Autentica o colaborador pelo CPF, sempre contra dados da Academy:
 *   1. online — validação exata no servidor;
 *   2. offline / servidor indisponível — filtro de Bloom sincronizado.
 * Uma resposta 401/400 do servidor é tratada como CPF inválido de fato
 * (não cai para o modo offline).
 */
export async function autenticar(cpf: string): Promise<AuthOutcome> {
  const cpfDigits = onlyDigits(cpf);

  let cpfInvalidoNoServidor = false;
  let mensagemServidor: string | null = null;

  try {
    const online = await loginOnline(cpfDigits);

    if (online.ok) {
      if (!online.profile.colaboradorId) {
        return {
          ok: false,
          message: "O Academy não retornou uma identidade válida para este colaborador.",
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
      await cacheOfflineProfile(cpfDigits, session);
      await bindLocalDataToUser(online.profile.colaboradorId);
      setSession(session);
      // Atualiza o filtro para habilitar o acesso offline futuro.
      void fetchCpfFilter().then(setCachedFilter).catch(() => {});
      return { ok: true, session };
    }

    if (online.status === 400 || online.status === 401) {
      cpfInvalidoNoServidor = true;
    } else {
      mensagemServidor = online.message;
    }
  } catch {
    // Sem conexão com o Córtex: segue para a validação offline.
  }

  if (cpfInvalidoNoServidor) {
    return {
      ok: false,
      message: "CPF não encontrado no Academy ou colaborador inativo.",
    };
  }

  // Validação offline pelo filtro de Bloom (servidor indisponível).
  const filtro = getCachedFilter();
  if (!filtro) {
    return {
      ok: false,
      message:
        mensagemServidor ??
        "Sem conexão e ainda sem dados do Academy neste dispositivo. Conecte-se uma vez para habilitar o acesso offline.",
    };
  }

  const podeEntrar = await cpfPodeEstarNoFiltro(filtro, cpfDigits);
  if (!podeEntrar) {
    return { ok: false, message: "CPF não encontrado no Academy." };
  }

  const cachedProfile = await getCachedOfflineProfile(cpfDigits);
  if (!cachedProfile?.colaboradorId) {
    return {
      ok: false,
      message:
        "Este colaborador ainda não entrou online neste dispositivo. Conecte-se uma vez para liberar o acesso offline com isolamento seguro.",
    };
  }

  const session: AuthSession = {
    colaboradorId: cachedProfile.colaboradorId,
    nome: cachedProfile.nome,
    cpfMascarado: cachedProfile.cpfMascarado || mascararCpf(cpfDigits),
    cpf: cpfDigits,
    perfil: cachedProfile.perfil,
    papelAcesso: cachedProfile.papelAcesso,
    token: null,
    origem: "offline",
    autenticadoEm: new Date().toISOString(),
  };
  await bindLocalDataToUser(cachedProfile.colaboradorId);
  setSession(session);
  return { ok: true, session };
}

/** Baixa/atualiza o filtro offline quando há conexão (best-effort). */
export async function sincronizarFiltroOffline(): Promise<void> {
  try {
    const filtro = await fetchCpfFilter();
    setCachedFilter(filtro);
  } catch {
    /* offline: mantém o filtro já armazenado */
  }
}
