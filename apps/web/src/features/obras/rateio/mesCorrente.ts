import { dataHojeEmBrasilia } from "../../../lib/tempo/fusoBrasilia";

/** Mês civil corrente da operação, independente do fuso do aparelho. */
export function mesCorrente(agora: Date = new Date()): string {
  return dataHojeEmBrasilia(agora).slice(0, 7);
}
