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
 * O toque bate em `/api/wake`, e não na prontidão. Os dois acordam o contêiner,
 * mas a prontidão cobra junto um round trip completo no object storage e a
 * evidência de release — trabalho que o login não usa. `/api/wake` aquece
 * exatamente o que entrar no sistema exige: o contêiner de pé, uma conexão no
 * pool e o banco fora da suspensão automática.
 *
 * Ele não decide nada — quem decide continua sendo a entrada. Falhar aqui é
 * irrelevante, porque a única função é fazer o contêiner começar a subir.
 */
const PRAZO_DO_TOQUE_MS = 60_000;

/*
 * Um toque só não bastava.
 *
 * Enquanto o contêiner sobe, o roteador na frente dele responde na hora — com
 * 502 ou 503, não com a espera. O toque então terminava em segundos, o
 * `emVoo` era liberado e nada mais insistia: a subida ficava pela metade até
 * alguém enviar o CPF, que era precisamente o que se queria evitar. Insistir
 * enquanto a tela de entrada está aberta não custa nada e é o que mantém a
 * subida andando.
 */
const ESPERAS_ENTRE_TOQUES_MS = [2_000, 4_000, 8_000, 16_000, 16_000];

let aquecendo = false;

export function despertarApi(): void {
  if (aquecendo || typeof fetch !== "function") {
    return;
  }
  if (typeof navigator !== "undefined" && !navigator.onLine) {
    return;
  }

  aquecendo = true;
  void insistir(0).finally(() => {
    aquecendo = false;
  });
}

async function insistir(tentativa: number): Promise<void> {
  if (typeof navigator !== "undefined" && !navigator.onLine) {
    return;
  }

  if (await tocar()) {
    return;
  }

  const espera = ESPERAS_ENTRE_TOQUES_MS[tentativa];
  if (espera === undefined) {
    return;
  }

  await new Promise<void>((resolve) => {
    globalThis.setTimeout(resolve, espera);
  });
  return insistir(tentativa + 1);
}

/** `true` quando a API respondeu por si — não pelo roteador que a antecede. */
async function tocar(): Promise<boolean> {
  const abortar = new AbortController();
  const prazo = globalThis.setTimeout(
    () => abortar.abort(),
    PRAZO_DO_TOQUE_MS,
  );

  try {
    const resposta = await fetch(apiUrl("/wake"), {
      method: "GET",
      // Sem credencial: acordar o serviço não é, e não pode virar, uma ação
      // autenticada. Sem cache: uma resposta guardada não acorda nada.
      credentials: "omit",
      cache: "no-store",
      signal: abortar.signal,
    });
    // 502 e 503 são o roteador dizendo que o contêiner ainda está subindo.
    // Qualquer outra resposta veio da própria API, que portanto já está de pé.
    return resposta.status !== 502 && resposta.status !== 503;
  } catch {
    return false;
  } finally {
    globalThis.clearTimeout(prazo);
  }
}
