import { fetchOfflineGrant } from "./authApi";
import { getSession, hasOnlineSession } from "./authSession";
import { verifySignedOfflineGrant } from "./offlineVault";
import type { OfflinePasswordVaultMetadata } from "./offlineVault.types";
import {
  listCollaborativeOfflineMetadata,
  replaceLegacyGrantAfterV3Save,
} from "./offlineVaultRepository";
import {
  hasLivePasswordVaultKey,
  isOfflinePasswordVaultMetadata,
  readLivePasswordVaultClaims,
  resealPasswordOfflineVault,
} from "./passwordOfflineVault";

/** Renova quando falta menos de um terço da validade assinada. */
const REMAINING_FRACTION_TO_RENEW = 1 / 3;

export type RenovacaoDoGrant =
  | "NAO_APLICAVEL"
  | "AINDA_VALIDO"
  | "RENOVADO"
  | "SEM_REDE"
  | "SENHA_NECESSARIA";

export async function precisaRenovar(
  vault: OfflinePasswordVaultMetadata,
  agora: number,
): Promise<boolean | "PASSWORD_REQUIRED"> {
  const claims = await readLivePasswordVaultClaims(vault, () => agora);
  if (claims === "PASSWORD_REQUIRED") {
    return claims;
  }
  return claimsNeedRenewal(claims.emitidoEm, claims.expiraEm, agora);
}

function claimsNeedRenewal(
  emitidoEm: string,
  expiraEm: string,
  agora: number,
): boolean | "PASSWORD_REQUIRED" {
  const issuedAt = Date.parse(emitidoEm);
  const expiresAt = Date.parse(expiraEm);
  const duration = expiresAt - issuedAt;
  if (
    !Number.isFinite(issuedAt) ||
    !Number.isFinite(expiresAt) ||
    duration <= 0
  ) {
    return "PASSWORD_REQUIRED";
  }
  return expiresAt - agora <= duration * REMAINING_FRACTION_TO_RENEW;
}

export async function renovarGrantOfflineSePreciso(
  agora: number = Date.now(),
): Promise<RenovacaoDoGrant> {
  const session = getSession();
  if (!session || !hasOnlineSession()) {
    return "NAO_APLICAVEL";
  }
  const stored = await listCollaborativeOfflineMetadata().catch(() => []);
  const passwordVaults: OfflinePasswordVaultMetadata[] = [];
  for (const record of stored) {
    if (isOfflinePasswordVaultMetadata(record)) {
      passwordVaults.push(record);
    }
  }
  if (passwordVaults.length === 0) {
    // Legacy v2 records stay untouched and keep their original signed expiry.
    return "NAO_APLICAVEL";
  }

  const withLiveKey = passwordVaults.filter(hasLivePasswordVaultKey);
  if (withLiveKey.length === 0) {
    return "SENHA_NECESSARIA";
  }

  const evaluated = await Promise.all(withLiveKey.map(async (vault) => {
    const claims = await readLivePasswordVaultClaims(vault, () => agora);
    return {
      vault,
      claims,
      renew: claims === "PASSWORD_REQUIRED"
        ? claims
        : claimsNeedRenewal(claims.emitidoEm, claims.expiraEm, agora),
    };
  }));
  const currentOwner = evaluated.filter((item) =>
    item.claims !== "PASSWORD_REQUIRED" &&
    item.claims.colaboradorId === session.colaboradorId
  );
  if (currentOwner.length === 0) {
    return "SENHA_NECESSARIA";
  }
  const expiring = currentOwner.filter((item) => item.renew === true);
  if (expiring.length === 0) {
    return "AINDA_VALIDO";
  }

  let signedGrant;
  try {
    signedGrant = await fetchOfflineGrant();
  } catch {
    return "SEM_REDE";
  }
  const verified = await verifySignedOfflineGrant(signedGrant, {
    now: () => agora,
  });
  if (
    verified.claims.versao !== 2 ||
    verified.claims.colaboradorId !== session.colaboradorId
  ) {
    return "NAO_APLICAVEL";
  }

  for (const item of expiring) {
    const resealed = await resealPasswordOfflineVault(
      item.vault,
      signedGrant,
      session.colaboradorId,
      () => agora,
    );
    if (resealed === "PASSWORD_REQUIRED") {
      return "SENHA_NECESSARIA";
    }
    await replaceLegacyGrantAfterV3Save(null, resealed);
  }
  return "RENOVADO";
}
