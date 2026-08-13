/**
 * O retrato do servidor para o rateio, e a razão de ele existir.
 *
 * <p>O aparelho já sabe calcular o rateio sozinho — é o que sustenta a tela
 * sem rede. O que ele não tem é <b>alcance</b>: a sincronização traz os RDOs
 * das obras que a pessoa abre, em passagens, e um mês inteiro de uma carteira
 * grande pode ainda não ter descido todo. O servidor fecha essa lacuna.
 *
 * <p>O que ele não faz é calcular. A conta é uma só no produto, e roda no
 * mesmo núcleo nos dois caminhos: daqui saem os mesmos apontamentos que sairiam
 * da leitura local, passando pela mesma função que decide quem é o encarregado
 * da frente. Servidor e aparelho não podem divergir porque não há duas contas.
 */

import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";

import { apontamentosDoRdo } from "./apontamentosDoAparelho";
import type { LeituraDeApontamentos } from "./apontamentosDoAparelho";
import type { ApontamentoDeMaoDeObra } from "./rateioDeMaoDeObra";

function texto(valor: unknown): string {
  return typeof valor === "string" ? valor.trim() : "";
}

/**
 * Traz do servidor os apontamentos do período.
 *
 * <p>Lança quando a rede falha ou o servidor recusa — quem chama decide cair
 * para a leitura local, que é o comportamento da tela.
 */
export async function buscarApontamentosDoServidor(
  inicio: string,
  fim: string,
): Promise<LeituraDeApontamentos & { completo: boolean }> {
  const response = await apiFetch(
    `/obras/rateio-mao-de-obra?inicio=${encodeURIComponent(inicio)}&fim=${encodeURIComponent(fim)}`,
  );
  const corpo = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(corpo, response.status));
  }
  return lerRespostaDoRateio(corpo);
}

/** A leitura da resposta, separada para poder ser conferida sem rede. */
export function lerRespostaDoRateio(
  corpo: unknown,
): LeituraDeApontamentos & { completo: boolean } {
  const vazio = {
    apontamentos: [] as ApontamentoDeMaoDeObra[],
    rdosLidos: 0,
    rdosSemConteudo: 0,
    rdosSemMaoDeObra: 0,
    completo: true,
  };
  if (typeof corpo !== "object" || corpo === null) return vazio;
  const bruto = corpo as Record<string, unknown>;
  if (!Array.isArray(bruto.rdos)) return vazio;

  const apontamentos: ApontamentoDeMaoDeObra[] = [];
  let rdosLidos = 0;
  let rdosSemMaoDeObra = 0;

  for (const item of bruto.rdos) {
    if (typeof item !== "object" || item === null) continue;
    const rdo = item as Record<string, unknown>;
    const id = texto(rdo.id);
    const obraId = texto(rdo.obraId);
    const dataRdo = texto(rdo.dataRdo);
    if (!id || !obraId || !dataRdo) continue;

    // O servidor sempre manda a lista, mesmo vazia — é o que distingue "não
    // havia ninguém" de "o conteúdo não chegou". Uma resposta antiga sem o
    // campo cai como lista vazia em vez de derrubar a tela.
    const maoObra = Array.isArray(rdo.maoObra) ? rdo.maoObra : [];
    const doRdo = apontamentosDoRdo({
      id,
      obraId,
      dataRdo,
      numeroRdo: texto(rdo.numeroRdo),
      payload: {
        encarregadoObra: rdo.encarregadoObra,
        apontadorRdo: rdo.apontadorRdo,
        // Quem assina o documento conta como presente, e por isso os campos da
        // assinatura atravessam inteiros: sem eles o retrato do servidor teria
        // menos gente que a leitura do aparelho, para os mesmos RDOs.
        apontadorColaboradorId: rdo.apontadorColaboradorId,
        // O ofício do apontador no cadastro (Academy): quando presente, a
        // função da linha dele no rateio é esta, não o rótulo do papel.
        apontadorFuncao: rdo.apontadorFuncao,
        preenchidoPor: rdo.preenchidoPor,
        // Quem preencheu assina em texto livre, sem identificador: o servidor
        // procura esse nome no cadastro e só manda o ofício quando ele aponta
        // para uma pessoa só. Vindo vazio, a linha fica com o rótulo do papel.
        preenchidoPorFuncao: rdo.preenchidoPorFuncao,
        obraNome: rdo.obraNome,
        maoObra,
      },
    });
    if (doRdo === null) continue;
    rdosLidos += 1;
    if (doRdo.length === 0) rdosSemMaoDeObra += 1;
    apontamentos.push(...doRdo);
  }

  return {
    apontamentos,
    rdosLidos,
    // O servidor não tem cabeçalho sem conteúdo: ele lê o banco, onde o RDO ou
    // existe inteiro ou não existe.
    rdosSemConteudo: 0,
    rdosSemMaoDeObra,
    completo: bruto.completo !== false,
  };
}
