const LAST_OBRA_KEY_PREFIX = "cortex.home.ultimaObra:";

export function colaboradorStorageKey(
  session: {
    colaboradorId: string | null;
    cpfMascarado: string;
  } | null,
): string | null {
  if (!session) {
    return null;
  }

  const identity =
    session.colaboradorId?.trim() ||
    session.cpfMascarado.trim();

  return identity
    ? `${LAST_OBRA_KEY_PREFIX}${identity}`
    : null;
}

export function getLastAccessedObraId(
  storageKey: string | null,
): string | null {
  if (!storageKey) {
    return null;
  }

  try {
    return localStorage.getItem(storageKey);
  } catch {
    return null;
  }
}

export function setLastAccessedObraId(
  storageKey: string | null,
  obraId: string,
): void {
  if (!storageKey || !obraId) {
    return;
  }

  try {
    localStorage.setItem(storageKey, obraId);
  } catch {
    // Sem storage disponível a Home apenas não lembra a obra.
  }
}
