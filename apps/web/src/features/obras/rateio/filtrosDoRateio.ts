/**
 * O que a tela mostra do rateio — sem mexer no que ele diz.
 *
 * <p>Aqui mora uma armadilha que vale explicar, porque a saída óbvia está
 * errada. Filtrar por obra <b>antes</b> da conta faria a fatia de cada pessoa
 * ser recalculada só entre as obras escolhidas: quem passou quinze dias numa e
 * quinze noutra apareceria com 100% ao filtrar uma delas. O número seria falso,
 * e falso de um jeito convincente — soma cem, parece certo, e diria que a
 * pessoa foi dedicada àquela obra o mês inteiro.
 *
 * <p>Por isso a conta é sempre feita sobre o período inteiro, com todas as
 * obras que a pessoa alcança, e o filtro age só na exibição: escolhe quais
 * linhas e quais colunas aparecem. A fatia de cada pessoa continua sendo a
 * fatia do tempo dela — e é por isso que, com filtro ligado, as fatias visíveis
 * podem somar menos de 100%. Somar menos é a verdade: o resto do tempo foi para
 * uma obra que a tela não está mostrando.
 */

import { normalizarNome } from "./rateioDeMaoDeObra";
import type {
  RateioDeColaborador,
  RateioDoPeriodo,
  TotalDaObra,
  TotalDoEncarregado,
} from "./rateioDeMaoDeObra";

export interface FiltroDaTela {
  /** Vazio significa todas as obras. */
  obraIds?: readonly string[];
  /** Casa com o nome ou com a função, sem acento e sem caixa. */
  busca?: string;
  /** Vazio significa todas as frentes. */
  encarregado?: string;
}

function casaComABusca(
  colaborador: RateioDeColaborador,
  termo: string,
): boolean {
  if (!termo) return true;
  const alvo = `${normalizarNome(colaborador.nome)} ${normalizarNome(
    colaborador.funcao,
  )}`;
  return alvo.includes(termo);
}

export function filtrarRateio(
  rateio: RateioDoPeriodo,
  filtro: FiltroDaTela,
): RateioDoPeriodo {
  const escolhidas =
    filtro.obraIds && filtro.obraIds.length > 0
      ? new Set(filtro.obraIds)
      : null;
  const termo = normalizarNome(filtro.busca ?? "");
  const frente = (filtro.encarregado ?? "").trim();

  const obraIds = escolhidas
    ? rateio.obraIds.filter((obraId) => escolhidas.has(obraId))
    : [...rateio.obraIds];

  const colaboradores = rateio.colaboradores.filter((colaborador) => {
    if (frente && colaborador.encarregado !== frente) return false;
    if (!casaComABusca(colaborador, termo)) return false;
    if (!escolhidas) return true;
    // Só entra quem esteve em alguma das obras escolhidas — quem passou o mês
    // inteiro fora delas não é linha em branco, é linha que não pertence a
    // este recorte.
    for (const obraId of colaborador.diasPorObra.keys()) {
      if (escolhidas.has(obraId)) return true;
    }
    return false;
  });

  const totaisPorObra = new Map<string, TotalDaObra>();
  const pessoasPorObra = new Map<string, Set<string>>();
  const diasComApontamento = new Set<string>();
  let diasApontadosNoTotal = 0;

  for (const colaborador of colaboradores) {
    diasApontadosNoTotal += colaborador.diasApontados;
    for (const [data, dia] of colaborador.dias) {
      const visiveis = escolhidas
        ? dia.obraIds.filter((obraId) => escolhidas.has(obraId))
        : dia.obraIds;
      if (visiveis.length > 0) diasComApontamento.add(data);
      for (const obraId of visiveis) {
        const total = totaisPorObra.get(obraId) ?? {
          obraId,
          diasApontados: 0,
          pessoas: 0,
        };
        total.diasApontados += dia.fracaoPorObra;
        totaisPorObra.set(obraId, total);
        const quadro = pessoasPorObra.get(obraId) ?? new Set<string>();
        quadro.add(colaborador.chave);
        pessoasPorObra.set(obraId, quadro);
      }
    }
  }

  for (const [obraId, quadro] of pessoasPorObra) {
    const total = totaisPorObra.get(obraId);
    if (total) total.pessoas = quadro.size;
  }

  const totaisPorEncarregado = new Map<string, TotalDoEncarregado>();
  for (const colaborador of colaboradores) {
    const total = totaisPorEncarregado.get(colaborador.encarregado) ?? {
      encarregado: colaborador.encarregado,
      pessoas: 0,
      diasApontados: 0,
    };
    total.pessoas += 1;
    total.diasApontados += colaborador.diasApontados;
    totaisPorEncarregado.set(colaborador.encarregado, total);
  }

  return {
    colaboradores,
    // Uma obra escolhida por quem filtra, mas sem ninguém no período, não vira
    // coluna de zeros na matriz.
    obraIds: obraIds.filter((obraId) => totaisPorObra.has(obraId)),
    totaisPorObra,
    totaisPorEncarregado: [...totaisPorEncarregado.values()].sort(
      (a, b) =>
        b.pessoas - a.pessoas ||
        a.encarregado.localeCompare(b.encarregado, "pt-BR"),
    ),
    diasComApontamento: [...diasComApontamento].sort(),
    diasApontadosNoTotal,
  };
}

/** As frentes que aparecem no período, para o seletor da tela. */
export function encarregadosDoRateio(
  rateio: RateioDoPeriodo,
): readonly string[] {
  const frentes = new Set<string>();
  for (const colaborador of rateio.colaboradores) {
    if (colaborador.encarregado) frentes.add(colaborador.encarregado);
  }
  return [...frentes].sort((a, b) => a.localeCompare(b, "pt-BR"));
}
