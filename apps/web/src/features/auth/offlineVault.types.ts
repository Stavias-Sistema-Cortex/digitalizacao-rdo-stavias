export type BaseOfflineGrantClaims = {
  colaboradorId: string;
  nome: string;
  papelAcesso: "ALFA" | "BETA";
  escopoGlobal: boolean;
  obraIds: string[];
  emitidoEm: string;
  expiraEm: string;
};

export type LegacyOfflineGrantClaims = BaseOfflineGrantClaims & {
  versao: 1;
};

export type CurrentOfflineGrantClaims = BaseOfflineGrantClaims & {
  versao: 2;
  authEpoch: number;
};

export type OfflineGrantClaims =
  | LegacyOfflineGrantClaims
  | CurrentOfflineGrantClaims;

/** Envelope assinado pelo servidor; não é uma credencial de sessão. */
export type SignedOfflineGrant = {
  keyId: string;
  payload: string;
  signature: string;
  publicKeySpki: string;
};

/** Somente metadados públicos e ciphertext podem sair da memória. */
export type OfflineVaultMetadata = {
  key: string;
  versao: 1;
  ownerId: string;
  scopeFingerprint: string;
  credentialId: string;
  rpId: string;
  prfSalt: string;
  iv: string;
  ciphertext: string;
  serverKeyFingerprint: string;
  atualizadoEm: string;
};

/** Metadados públicos para liberar um grant de CPF colaborativo offline. */
export type OfflineCpfGrantMetadata = {
  key: string;
  versao: 2;
  cpfSalt: string;
  cpfVerifier: string;
  ownerId: string;
  scopeFingerprint: string;
  signedGrant: SignedOfflineGrant;
  serverKeyFingerprint: string;
  atualizadoEm: string;
};

export type OfflineUnlockResult = "UNLOCKED" | "PRF_UNAVAILABLE";
