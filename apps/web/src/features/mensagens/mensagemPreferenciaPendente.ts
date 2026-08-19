import type { PreferenciaDeConversaLocal } from "../../lib/db/db.types";

export type CampoPendenteDePreferencia = "ARQUIVAMENTO" | "LIMPEZA";

export interface PendenciasDaPreferencia {
  arquivamento: boolean;
  limpeza: boolean;
}

/**
 * Lê a pendência por campo e migra em memória os registros da versão antiga.
 *
 * <p>A versão antiga tinha apenas um booleano para os dois gestos. Seus
 * registros não carregavam os relógios por campo. Os valores atuais não
 * revelam qual campo mudou: {@code limpoAte} preenchido também pode coexistir
 * com um desarquivamento pendente, e o caso espelho vale para reabrir. Como os
 * dois comandos são idempotentes, a migração segura reenvia ambos.</p>
 */
export function pendenciasDaPreferencia(
  preference: PreferenciaDeConversaLocal,
): PendenciasDaPreferencia {
  if (
    typeof preference.arquivadoPendente === "boolean" &&
    typeof preference.limpoPendente === "boolean"
  ) {
    return {
      arquivamento: preference.arquivadoPendente,
      limpeza: preference.limpoPendente,
    };
  }
  if (!preference.pendente) {
    return { arquivamento: false, limpeza: false };
  }

  return { arquivamento: true, limpeza: true };
}

export function camposPendentesDaPreferencia(
  preference: PreferenciaDeConversaLocal,
): CampoPendenteDePreferencia[] {
  const pending = pendenciasDaPreferencia(preference);
  return [
    ...(pending.arquivamento ? ["ARQUIVAMENTO" as const] : []),
    ...(pending.limpeza ? ["LIMPEZA" as const] : []),
  ];
}

export function preferenciaComPendencias(
  preference: PreferenciaDeConversaLocal,
  pending: PendenciasDaPreferencia,
): PreferenciaDeConversaLocal {
  return {
    ...preference,
    arquivadoPendente: pending.arquivamento,
    limpoPendente: pending.limpeza,
    pendente: pending.arquivamento || pending.limpeza,
  };
}
