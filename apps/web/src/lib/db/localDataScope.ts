const USER_SCOPED_LOCAL_STORAGE_KEYS = [
  "cortex:stavia:chat:operacional",
  "cortex:stavia:last-context",
] as const;

type LocalStorageRemover = Pick<Storage, "removeItem">;

/**
 * A conversa e o último contexto da StavIA são auxiliares de interface, mas
 * podem conter conteúdo operacional. Eles seguem a mesma fronteira de
 * identidade do IndexedDB e não podem sobreviver a uma troca de usuário.
 */
export function clearUserScopedLocalStorage(
  storage?: LocalStorageRemover,
): void {
  const target =
    storage ??
    (typeof window === "undefined" ? null : window.localStorage);

  if (!target) {
    return;
  }

  for (const key of USER_SCOPED_LOCAL_STORAGE_KEYS) {
    target.removeItem(key);
  }
}
