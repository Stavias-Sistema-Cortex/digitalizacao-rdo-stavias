import { getLocalRdo } from "../../../lib/db/rdoRepository";
import {
  rdoDraftFromLocalRecord,
  saveExistingRdoDraftAtomically,
} from "../../../lib/db/localRdoService";
import { kmDoPonto, type Coordenada, type EixoDaObra } from "./eixoDaObra";
import type { PontoGeografico } from "./rascunhoDoTrecho";

/**
 * A correção feita no mapa, escrita de volta no apontamento do RDO.
 *
 * <p>A linha derivada não é desenho: ela nasce na leitura, do quilômetro que
 * mora no apontamento. Por isso arrastar um extremo dela não pode gravar
 * geometria nenhuma — gravar criaria a segunda cópia da posição que este
 * trabalho inteiro existe para evitar. O que a correção faz é o caminho
 * inverso: o ponto arrastado vira quilômetro pela régua do eixo, e o
 * quilômetro é escrito onde ele sempre morou, na linha de serviço do RDO.
 *
 * <p>É por isso que apagar no mapa não apaga no RDO e editar no mapa edita: as
 * duas ações falam do mesmo dado, mas só uma delas tem o que dizer sobre ele.
 * Apagar a linha derivada não significa nada — ela não existe como registro.
 * Mover os extremos significa exatamente uma coisa, e é essa.
 */

/** Casas decimais do quilômetro escrito de volta, como a base já o guarda. */
const CASAS_DO_KM = 3;

export interface CorrecaoDeKm {
  obraId: string;
  rdoId: string;
  /** A linha de serviço do RDO de onde o quilômetro veio. */
  execucaoId: string;
  eixo: EixoDaObra;
  inicio: PontoGeografico;
  fim: PontoGeografico;
}

export interface KmCorrigido {
  kmInicial: string;
  kmFinal: string;
}

function comoCoordenada(ponto: PontoGeografico): Coordenada {
  return [ponto.lng, ponto.lat];
}

/**
 * Escreve o quilômetro em pt-BR, que é como o resto do Córtex o lê.
 *
 * <p>Três casas porque é a precisão que a rodovia usa — o km 206,822 é uma
 * marcação real, e arredondá-lo para 206,8 moveria o trecho oitenta metros.
 */
function escreverKm(valor: number): string {
  return valor.toFixed(CASAS_DO_KM).replace(".", ",");
}

/**
 * Converte os dois extremos arrastados em quilômetro, pela régua do eixo.
 *
 * <p>Devolve `null` quando o eixo não consegue situar algum dos pontos — sem
 * régua utilizável não há correção a fazer, e inventar um quilômetro aqui
 * escreveria no RDO uma medida que ninguém declarou.
 */
export function kmDaCorrecao(
  eixo: EixoDaObra,
  inicio: PontoGeografico,
  fim: PontoGeografico,
): KmCorrigido | null {
  const kmInicial = kmDoPonto(eixo, comoCoordenada(inicio));
  const kmFinal = kmDoPonto(eixo, comoCoordenada(fim));
  if (kmInicial === null || kmFinal === null) {
    return null;
  }
  return {
    kmInicial: escreverKm(kmInicial),
    kmFinal: escreverKm(kmFinal),
  };
}

/**
 * Aplica a correção ao RDO, no aparelho, e deixa a fila levá-la ao servidor.
 *
 * <p>O RDO precisa ser da obra que está aberta no mapa. Um apontamento de
 * outra obra nunca deveria chegar aqui — o mapa lê uma obra só —, mas escrever
 * quilômetro no RDO errado é o tipo de engano que ninguém percebe depois, e a
 * conferência custa uma linha.
 */
export async function corrigirKmPeloMapa(
  correcao: CorrecaoDeKm,
): Promise<KmCorrigido> {
  const km = kmDaCorrecao(correcao.eixo, correcao.inicio, correcao.fim);
  if (!km) {
    throw new Error(
      "O eixo não consegue situar este ponto. Confira a quilometragem das pontas do eixo.",
    );
  }

  const registro = await getLocalRdo(correcao.rdoId);
  if (!registro) {
    throw new Error(
      "O RDO deste trecho não está neste aparelho. Abra-o uma vez antes de corrigir pelo mapa.",
    );
  }
  if (registro.obraId !== correcao.obraId) {
    throw new Error("Este apontamento é de outra obra.");
  }

  const draft = rdoDraftFromLocalRecord(registro);
  const linha = draft.servicosExecutados.find(
    (servico) => servico.localId === correcao.execucaoId,
  );
  if (!linha) {
    throw new Error(
      "A linha de serviço deste trecho não está no RDO deste aparelho.",
    );
  }

  await saveExistingRdoDraftAtomically({
    ...draft,
    servicosExecutados: draft.servicosExecutados.map((servico) =>
      servico.localId === correcao.execucaoId
        ? {
            ...servico,
            trechoInicial: km.kmInicial,
            trechoFinal: km.kmFinal,
          }
        : servico,
    ),
  });

  return km;
}
