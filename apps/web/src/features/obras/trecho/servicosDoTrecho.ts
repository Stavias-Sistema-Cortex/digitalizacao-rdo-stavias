import type { SegmentoTrecho } from "./trechoGeometry";

/** Quantos timbres existem em `index.css` antes de a série recomeçar. */
export const TIMBRES_DE_SERVICO = 6;

/**
 * Nome do serviço como ele identifica um bloco, ou nada.
 *
 * O `subtrecho` serve de rótulo quando o serviço não foi nomeado, mas não
 * serve de identidade: dois blocos do mesmo serviço em subtrechos diferentes
 * são o mesmo trabalho, e separá-los por timbre diria o contrário.
 */
function nomeDoServico(segmento: SegmentoTrecho): string | null {
  const nome = segmento.servicoNome?.trim();
  return nome ? nome : null;
}

/**
 * Os serviços distintos de uma leitura, na ordem em que aparecem no trecho.
 *
 * A ordem é a do próprio trecho — quem lê da esquerda para a direita encontra
 * o primeiro timbre primeiro. Um índice por nome do serviço, e não por hash,
 * porque hash produz cor estável mas arbitrária: dois serviços vizinhos podem
 * cair em tons quase iguais, e ninguém consegue corrigir isso sem renomear o
 * serviço. Aqui a separação é garantida por construção.
 */
export function servicosDistintos(
  segmentos: readonly SegmentoTrecho[],
): string[] {
  const vistos: string[] = [];
  for (const segmento of segmentos) {
    const nome = nomeDoServico(segmento);
    if (nome && !vistos.includes(nome)) {
      vistos.push(nome);
    }
  }
  return vistos;
}

/**
 * O canal do serviço só existe quando há o que distinguir.
 *
 * Com um serviço só, o timbre e sua legenda ocupariam espaço para repetir o
 * que o rótulo dentro do bloco já diz. A distinção nasce da necessidade de
 * distinguir; sem ela, a tela fica como estava.
 */
export function timbresDeServico(
  segmentos: readonly SegmentoTrecho[],
): Map<string, number> {
  const servicos = servicosDistintos(segmentos);
  if (servicos.length < 2) {
    return new Map();
  }
  return new Map(
    servicos.map((nome, indice) => [
      nome,
      (indice % TIMBRES_DE_SERVICO) + 1,
    ]),
  );
}

/** O timbre de um bloco, ou nada quando o canal não está em uso. */
export function timbreDoSegmento(
  segmento: SegmentoTrecho,
  timbres: ReadonlyMap<string, number>,
): number | null {
  const nome = nomeDoServico(segmento);
  return nome ? timbres.get(nome) ?? null : null;
}
