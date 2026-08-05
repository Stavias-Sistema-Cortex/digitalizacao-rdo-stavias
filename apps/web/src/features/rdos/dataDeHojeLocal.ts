/**
 * Hoje no fuso do aparelho, no formato que o input de data entende.
 *
 * <p>Data local e não UTC de propósito: às 21h em Pirassununga o dia UTC já
 * virou, e o apontador receberia amanhã como sugestão de hoje.
 */
export function dataDeHojeLocal(agora = new Date()): string {
  const mes = `${agora.getMonth() + 1}`.padStart(2, "0");
  const dia = `${agora.getDate()}`.padStart(2, "0");
  return `${agora.getFullYear()}-${mes}-${dia}`;
}
