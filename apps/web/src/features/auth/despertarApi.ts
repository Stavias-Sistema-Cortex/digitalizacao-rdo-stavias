import { apiUrl } from "../../lib/api/apiEndpoint";

/**
 * Acorda a API enquanto ninguém está esperando por ela.
 *
 * O serviço sobe sob demanda, e a subida só começava quando o CPF era enviado
 * — ou seja, com alguém parado na frente da tela olhando o botão girar. Mas a
 * tela de entrada fica aberta antes disso: abrir o aplicativo, achar o campo,
 * digitar e conferir onze dígitos leva dezenas de segundos, e nesse tempo o
 * servidor pode estar subindo em paralelo.
 *
 * Este toque é deliberadamente burro: um GET no endereço público de prontidão,
 * sem credencial, sem leitura do corpo e sem tratamento de erro. Ele não
 * decide nada — quem decide continua sendo a entrada. Falhar aqui é
 * irrelevante, porque a única função é fazer o contêiner começar a subir.
 */
const PRAZO_DO_TOQUE_MS = 60_000;

let emVoo: Promise<void> | null = null;

export function despertarApi(): void {
  if (emVoo || typeof fetch !== "function") {
    return;
  }
  if (typeof navigator !== "undefined" && !navigator.onLine) {
    return;
  }

  const abortar = new AbortController();
  const prazo = globalThis.setTimeout(
    () => abortar.abort(),
    PRAZO_DO_TOQUE_MS,
  );

  emVoo = fetch(apiUrl("/readiness"), {
    method: "GET",
    // Sem credencial: acordar o serviço não é, e não pode virar, uma ação
    // autenticada. Sem cache: uma resposta guardada não acorda nada.
    credentials: "omit",
    cache: "no-store",
    signal: abortar.signal,
  })
    .then(() => undefined)
    .catch(() => undefined)
    .finally(() => {
      globalThis.clearTimeout(prazo);
      emVoo = null;
    });
}
