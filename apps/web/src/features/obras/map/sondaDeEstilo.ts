/**
 * Pergunta à rede, antes de montar, se o estilo remoto tem como funcionar.
 *
 * Todo conserto anterior deste painel foi feito por dentro do renderizador:
 * esperar o evento certo, interpretar a mensagem de erro certa, medir o tile
 * carregado. Nada disso responde à pergunta que importa, porque o MapLibre não
 * a faz — ele monta com o que tem e chama de pronto. Uma fonte inteira que
 * falhou não deixa tile pendente, e um estilo apontando para um host mudo
 * produz um mapa formalmente carregado e visualmente vazio.
 *
 * A sonda faz a pergunta direta: buscar o estilo, ler o JSON, e buscar também
 * as fontes que ele declara por URL. Quem responde a tudo isso desenha; quem
 * não responde é descartado antes de ocupar a tela. É um `fetch` comum, então
 * passa pelo mesmo service worker que serve o painel — inclusive por uma
 * entrada guardada com defeito, que é justamente um dos modos de falha que
 * precisam ser reprovados aqui em vez de virarem retângulo mudo.
 */

import { fontesDeclaradas } from "./mapProvider";

/** Além disso, esperar não é melhor do que desenhar o basemap embutido. */
export const LIMITE_SONDA_MS = 8_000;

export interface ResultadoDaSonda {
  usavel: boolean;
  /** Por que o estilo foi descartado; `null` quando ele passou. */
  motivo: string | null;
}

const APROVADO: ResultadoDaSonda = Object.freeze({
  usavel: true,
  motivo: null,
});

interface OpcoesDaSonda {
  buscar?: typeof fetch;
  limiteMs?: number;
}

async function buscarJson(
  url: string,
  buscar: typeof fetch,
  limiteMs: number,
): Promise<{ ok: true; corpo: unknown } | { ok: false; motivo: string }> {
  const controlador = new AbortController();
  const expirar = setTimeout(() => controlador.abort(), limiteMs);
  try {
    const resposta = await buscar(url, {
      signal: controlador.signal,
      // A resposta precisa ser legível: o renderizador não consegue usar uma
      // resposta opaca, e guardá-la é como o painel ficava branco para sempre.
      mode: "cors",
      credentials: "omit",
    });
    if (!resposta.ok) {
      return { ok: false, motivo: `foi recusado pelo servidor (${resposta.status})` };
    }
    if (resposta.type === "opaque") {
      return { ok: false, motivo: "veio numa resposta opaca, que não pode ser lida" };
    }
    return { ok: true, corpo: await resposta.json() };
  } catch (erro) {
    return {
      ok: false,
      motivo:
        erro instanceof DOMException && erro.name === "AbortError"
          ? "não respondeu a tempo"
          : "não foi entregue",
    };
  } finally {
    clearTimeout(expirar);
  }
}

/**
 * Diz se vale montar o mapa com este estilo remoto.
 *
 * Estilo embutido não tem o que sondar — não há host envolvido, e é sempre
 * usável. Nas fontes declaradas, a primeira que falhar já reprova o conjunto:
 * um estilo sem sua malha é um fundo liso, e fundo liso é o retângulo mudo.
 */
export async function sondarEstilo(
  styleUrl: string | null,
  opcoes: OpcoesDaSonda = {},
): Promise<ResultadoDaSonda> {
  if (!styleUrl) {
    return APROVADO;
  }
  if (!/^https?:/i.test(styleUrl)) {
    // O Mapbox endereça o estilo por `mapbox://`, que o runtime dele resolve
    // internamente e nenhum `fetch` alcança. Sondar aqui reprovaria sempre um
    // provider pago que funciona — e reprovar o que funciona é tão ruim
    // quanto aprovar o que não funciona.
    return APROVADO;
  }
  const buscar = opcoes.buscar ?? globalThis.fetch?.bind(globalThis);
  if (!buscar) {
    // Ambiente sem `fetch` não tem como sondar; deixar montar é melhor do que
    // reprovar um estilo que talvez funcione.
    return APROVADO;
  }
  const limiteMs = opcoes.limiteMs ?? LIMITE_SONDA_MS;

  const estilo = await buscarJson(styleUrl, buscar, limiteMs);
  if (!estilo.ok) {
    return { usavel: false, motivo: `o estilo do mapa ${estilo.motivo}` };
  }

  const fontes = fontesDeclaradas(estilo.corpo);
  for (const fonte of fontes) {
    const resultado = await buscarJson(fonte, buscar, limiteMs);
    if (!resultado.ok) {
      return {
        usavel: false,
        motivo: `a malha do mapa ${resultado.motivo}`,
      };
    }
  }

  return APROVADO;
}
