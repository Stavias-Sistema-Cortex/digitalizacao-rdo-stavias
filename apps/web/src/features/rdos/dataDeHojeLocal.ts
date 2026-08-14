import { dataHojeEmBrasilia } from "../../lib/tempo/fusoBrasilia";

/**
 * Hoje no fuso operacional de Brasília, no formato que o input de data entende.
 *
 * <p>O nome é mantido por compatibilidade com os consumidores existentes; o
 * dia deixou de depender do fuso configurado no aparelho.
 */
export function dataDeHojeLocal(agora = new Date()): string {
  return dataHojeEmBrasilia(agora);
}
