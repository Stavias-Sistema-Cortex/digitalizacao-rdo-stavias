import { scopeFingerprint } from "../../lib/db/localDataNamespace";
import { fetchOfflineGrant } from "./authApi";
import { hasOnlineSession, getSession } from "./authSession";
import { offlineGrantScopeMaterial, verifySignedOfflineGrant } from "./offlineVault";
import type { OfflineCpfGrantMetadata } from "./offlineVault.types";
import {
  listCollaborativeOfflineGrantsForOwner,
  saveCollaborativeOfflineGrantMetadata,
} from "./offlineVaultRepository";

/**
 * O grant offline precisa ser renovado enquanto há rede.
 *
 * <p>Ele nascia no login e morria em 24 horas, sem nunca ser reemitido. Passado
 * esse prazo o aparelho parava de abrir até os dados que já estavam nele:
 * "o grant offline expirou ou possui validade inválida", numa tela que existe
 * justamente para funcionar quando não há servidor a quem pedir outro. Quem
 * passou o dia em campo chegava no dia seguinte sem acesso ao próprio
 * trabalho.
 *
 * <p>Enquanto existe sessão online, pedir um grant novo é barato e não depende
 * de nada que a pessoa precise digitar. É o único momento em que dá para
 * fazer isso, e por isso é onde se faz.
 */

/** Renova quando falta menos de um terço da validade. */
const FRACAO_RESTANTE_PARA_RENOVAR = 1 / 3;

export type RenovacaoDoGrant =
  /** Não havia grant guardado, ou não havia sessão online para pedir outro. */
  | "NAO_APLICAVEL"
  /** Ainda há validade de sobra; nada foi pedido ao servidor. */
  | "AINDA_VALIDO"
  | "RENOVADO"
  /** A rede não respondeu. O grant guardado continua valendo até vencer. */
  | "SEM_REDE";

/**
 * Diz se este grant já está perto o suficiente do fim para ser trocado.
 *
 * <p>Um terço restante dá margem para vários dias de tentativa antes de
 * expirar, sem pedir um grant novo a cada abertura do aplicativo.
 */
export async function precisaRenovar(
  grant: OfflineCpfGrantMetadata,
  agora: number,
): Promise<boolean> {
  let claims;
  try {
    claims = (await verifySignedOfflineGrant(grant.signedGrant)).claims;
  } catch {
    // Já vencido, ou ilegível: é exatamente o estado que trancava o aparelho.
    return true;
  }
  const emitidoEm = Date.parse(claims.emitidoEm);
  const expiraEm = Date.parse(claims.expiraEm);
  if (!Number.isFinite(emitidoEm) || !Number.isFinite(expiraEm)) {
    return true;
  }
  const duracao = expiraEm - emitidoEm;
  if (duracao <= 0) {
    return true;
  }
  return expiraEm - agora <= duracao * FRACAO_RESTANTE_PARA_RENOVAR;
}

export async function renovarGrantOfflineSePreciso(
  agora: number = Date.now(),
): Promise<RenovacaoDoGrant> {
  const sessao = getSession();
  if (!sessao || !hasOnlineSession()) {
    return "NAO_APLICAVEL";
  }
  const guardados = await listCollaborativeOfflineGrantsForOwner(
    sessao.colaboradorId,
  ).catch(() => []);
  if (guardados.length === 0) {
    return "NAO_APLICAVEL";
  }
  const avaliados = await Promise.all(
    guardados.map(async (grant) => ({
      grant,
      renovar: await precisaRenovar(grant, agora),
    })),
  );
  const vencendo = avaliados
    .filter((item) => item.renovar)
    .map((item) => item.grant);
  if (vencendo.length === 0) {
    return "AINDA_VALIDO";
  }

  let assinado;
  try {
    assinado = await fetchOfflineGrant();
  } catch {
    // Sem grant novo, o guardado segue valendo até vencer. Insistir agora não
    // adianta; a próxima janela com rede tenta de novo.
    return "SEM_REDE";
  }

  const verificado = await verifySignedOfflineGrant(assinado);
  if (verificado.claims.colaboradorId !== sessao.colaboradorId) {
    // O servidor devolveu o grant de outra identidade. Nada é gravado.
    return "NAO_APLICAVEL";
  }
  const escopo = await scopeFingerprint(
    verificado.claims.colaboradorId,
    offlineGrantScopeMaterial(verificado.claims),
  );

  for (const grant of vencendo) {
    // A chave é o resumo do CPF e não muda: quem abre o registro continua
    // sendo a mesma pessoa, com o mesmo CPF. O que se troca é o grant.
    await saveCollaborativeOfflineGrantMetadata({
      ...grant,
      signedGrant: assinado,
      scopeFingerprint: escopo,
      serverKeyFingerprint: verificado.fingerprint,
      atualizadoEm: new Date(agora).toISOString(),
    });
  }
  return "RENOVADO";
}
