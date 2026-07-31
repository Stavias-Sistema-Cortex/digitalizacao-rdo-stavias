import { ApiTransportError } from "../../../lib/api/apiClient";

/**
 * Decide se uma falha justifica ler o que o dispositivo já tem.
 *
 * Só a ausência de transporte — sem rede, tempo esgotado — significa "estamos
 * offline". Um 403, um 404 ou um 500 são respostas do servidor e precisam
 * chegar à pessoa como erro: apresentar uma obra sem permissão como se ela
 * apenas não tivesse geometria seria mentir sobre o estado do sistema.
 */
export function falhaEhAusenciaDeRede(motivo: unknown): boolean {
  if (motivo instanceof ApiTransportError) {
    return motivo.kind === "CONNECTION" || motivo.kind === "TIMEOUT";
  }
  // O navegador é a última palavra quando o erro não é tipado, por exemplo
  // quando o próprio `fetch` falha antes de o cliente envolvê-lo.
  return typeof navigator !== "undefined" && navigator.onLine === false;
}
