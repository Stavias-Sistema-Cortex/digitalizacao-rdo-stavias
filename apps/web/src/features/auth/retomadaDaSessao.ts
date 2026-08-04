import {
  fetchIsolatedSessionOwner,
  readResponseBody,
} from "../../lib/api/apiClient";
import { parseAuthProfile } from "./authApi";
import { getSession, hasOfflineSession, setSession } from "./authSession";
import { clearOfflineGrant } from "./offlineVault";
import { clearRemoteSessionIsolation } from "./remoteSessionIsolation";

/**
 * A volta do modo offline para o modo online, sem pedir o CPF de novo.
 *
 * <p>Quem abriu os dados offline ficava preso ali: toda chamada era recusada
 * antes de sair do aparelho e a sincronização respondia "faça login novamente
 * para sincronizar" — mesmo com a rede de volta e a sessão do servidor ainda
 * viva. Redigitar o CPF não acrescentava segurança nenhuma; só atrasava a
 * subida do trabalho de campo, que é justamente o que não pode esperar.
 *
 * <p>O isolamento existe por um motivo real, e ele não é ignorado aqui. Depois
 * de os dados serem abertos offline, o cookie HttpOnly deixa de provar que
 * pertence a quem está neste aparelho: pode ter sobrado da sessão de outra
 * pessoa, e usá-lo faria uma pessoa agir como a outra. A pergunta que resolve
 * isso é uma só — de quem é este cookie? — e o servidor a responde. Se a
 * resposta for a mesma pessoa que abriu o grant aqui, o cookie é dela e não há
 * o que isolar. Se for outra, o isolamento fica de pé.
 */
export type RetomadaDaSessao =
  /** Não havia sessão offline: nada a retomar. */
  | "NAO_APLICAVEL"
  /** O servidor confirmou o mesmo dono; a sessão online está de volta. */
  | "RETOMADA"
  /** Sem resposta do servidor. Continua offline, e se tenta de novo depois. */
  | "SEM_REDE"
  /** O cookie não serve — expirou, ou é de outra pessoa. Precisa de login. */
  | "PRECISA_LOGIN";

export async function retomarSessaoOnline(): Promise<RetomadaDaSessao> {
  if (!hasOfflineSession()) {
    return "NAO_APLICAVEL";
  }
  const donoOffline = getSession()?.colaboradorId ?? null;
  if (!donoOffline) {
    return "NAO_APLICAVEL";
  }

  let response: Response;
  try {
    response = await fetchIsolatedSessionOwner();
  } catch {
    // Sem transporte não há o que concluir. O aparelho segue offline, com os
    // dados abertos, e a próxima volta de rede tenta de novo.
    return "SEM_REDE";
  }

  if (response.status === 401) {
    return "PRECISA_LOGIN";
  }
  if (!response.ok) {
    // Resposta do servidor que não é identidade não autoriza soltar nada.
    return "PRECISA_LOGIN";
  }

  let perfil;
  try {
    perfil = parseAuthProfile(await readResponseBody(response));
  } catch {
    return "PRECISA_LOGIN";
  }

  if (perfil.colaboradorId !== donoOffline) {
    // O cookie é de outra pessoa. É exatamente o caso que o isolamento existe
    // para barrar, e ele continua de pé.
    return "PRECISA_LOGIN";
  }

  // Mesma pessoa: o cookie é dela, e a sessão online volta a valer. O grant
  // offline sai de cena porque a sessão do servidor passa a ser a autoridade;
  // ele é reemitido enquanto houver rede.
  clearRemoteSessionIsolation();
  clearOfflineGrant();
  setSession(perfil);
  return "RETOMADA";
}
