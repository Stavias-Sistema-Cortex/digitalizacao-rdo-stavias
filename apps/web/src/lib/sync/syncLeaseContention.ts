/**
 * Disputa de exclusividade entre abas — não é falha de sincronização.
 *
 * Duas abas abertas competem pelo mesmo lease, e as duas saídas dessa disputa
 * são normais: ou esta aba não conseguiu assumir, ou assumiu e perdeu para
 * outra no meio do caminho. Nos dois casos quem está sincronizando é a outra
 * aba, e o trabalho está sendo feito.
 *
 * A distinção importa porque a tela trata erro como dado perdido: anunciar
 * "Falha na sincronização" para uma disputa ganha faz quem está em campo achar
 * que o apontamento não subiu, e a leitura verdadeira é a oposta. Os pontos de
 * verificação do lease existem justamente para esta aba parar de escrever
 * quando outra assume — interromper é o comportamento projetado, não o defeito.
 *
 * Vive separado de `syncExecutionLease` de propósito. Quem só precisa
 * reconhecer a disputa é a camada de tela, e aquele módulo abre o IndexedDB no
 * topo: importá-lo de um componente arrastava o banco inteiro para dentro do
 * render, junto com os efeitos de janela que ele registra. Aqui não há
 * dependência nenhuma — é só o vocabulário do erro.
 */

export class SyncLeaseUnavailableError extends Error {
  readonly code = "SYNC_LEASE_UNAVAILABLE";

  constructor() {
    super("A sincronização já está ativa em outra aba.");
    this.name = "SyncLeaseUnavailableError";
  }
}

export class SyncLeaseLostError extends Error {
  readonly code = "SYNC_LEASE_LOST";

  constructor() {
    super("A sincronização perdeu a exclusividade para outra aba.");
    this.name = "SyncLeaseLostError";
  }
}

function hasCode(error: unknown, code: string): boolean {
  return Boolean(
    error &&
      typeof error === "object" &&
      "code" in error &&
      error.code === code,
  );
}

export function isSyncLeaseUnavailableError(
  error: unknown,
): error is SyncLeaseUnavailableError {
  return hasCode(error, "SYNC_LEASE_UNAVAILABLE");
}

export function isSyncLeaseLostError(
  error: unknown,
): error is SyncLeaseLostError {
  return hasCode(error, "SYNC_LEASE_LOST");
}

/** As duas metades da disputa, que precisam ser reconhecidas juntas. */
export function isSyncLeaseContentionError(error: unknown): boolean {
  return isSyncLeaseUnavailableError(error) || isSyncLeaseLostError(error);
}
