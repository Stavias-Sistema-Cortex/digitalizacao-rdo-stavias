import { ApiTransportError } from "../../../lib/api/apiClient";

/**
 * Decide se uma falha justifica ler o que o dispositivo já tem.
 *
 * Ausência de transporte — sem rede, tempo esgotado — significa "estamos
 * offline". Um 403, um 404 ou um 500 são respostas do servidor e precisam
 * chegar à pessoa como erro: apresentar uma obra sem permissão como se ela
 * apenas não tivesse geometria seria mentir sobre o estado do sistema.
 *
 * <p>Sessão remota isolada entra no primeiro grupo, e não no segundo. Quem
 * abriu os dados pelo modo offline não tem sessão de servidor para gastar, e
 * toda chamada é recusada antes de sair do aparelho — o que é, do ponto de
 * vista da leitura, exatamente estar sem servidor. Tratá-la como resposta do
 * servidor fazia o mapa anunciar falha em cima de camadas que estavam
 * inteiras ali no dispositivo, e era esse o motivo de o modo offline mostrar
 * erro no lugar do que ele existe para mostrar.
 */
export function falhaEhAusenciaDeRede(motivo: unknown): boolean {
  if (motivo instanceof ApiTransportError) {
    return (
      motivo.kind === "CONNECTION" ||
      motivo.kind === "TIMEOUT" ||
      motivo.kind === "REMOTE_SESSION_ISOLATED"
    );
  }
  // O navegador é a última palavra quando o erro não é tipado, por exemplo
  // quando o próprio `fetch` falha antes de o cliente envolvê-lo.
  return typeof navigator !== "undefined" && navigator.onLine === false;
}
