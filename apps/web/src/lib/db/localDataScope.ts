const LEGACY_PRIVATE_LOCAL_STORAGE_KEYS = [
  "cortex:stavia:chat:operacional",
  "cortex:stavia:last-context",
] as const;

/**
 * Remove somente chaves históricas do assistente que podem conter conteúdo
 * operacional privado. Elas nunca são lidas nem recriadas pelo runtime atual.
 */
export function clearUserScopedLocalStorage(): void {
  const target = typeof window === "undefined" ? null : window.localStorage;

  if (!target) {
    return;
  }

  for (const key of LEGACY_PRIVATE_LOCAL_STORAGE_KEYS) {
    target.removeItem(key);
  }
}
