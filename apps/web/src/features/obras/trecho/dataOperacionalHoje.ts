import { dataHojeEmBrasilia } from "../../../lib/tempo/fusoBrasilia";

/** Dia civil da operação, independente do fuso configurado no aparelho. */
export function dataOperacionalHoje(agora: Date = new Date()): string {
  return dataHojeEmBrasilia(agora);
}
