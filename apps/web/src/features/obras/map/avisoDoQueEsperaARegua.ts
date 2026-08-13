import type { ApontamentosSemLinha } from "./obraMapApi";

/**
 * O que dizer quando o RDO apontou o quilômetro e o mapa não desenhou.
 *
 * <p>Esta tela ficava vazia em silêncio, e o silêncio tinha duas causas que
 * pedem gestos opostos: a obra sem eixo cadastrado — não há régua sobre a qual
 * apoiar quilômetro nenhum — e o apontamento que cai fora da faixa coberta
 * pela régua que existe. Quem apontou km 200 a 202 no RDO e abriu o mapa via a
 * mesma coisa nos dois casos: nada.
 *
 * <p>A frase nomeia a <b>data do RDO</b>, e isso não é enfeite. O mapa é lido
 * num dia e o trabalho aconteceu em outro — RDO do dia 8 aberto no dia 12 é o
 * caso comum, não a exceção —, e o cabeçalho da tela só sabia falar do
 * instante da leitura. Sem a data do apontamento escrita aqui, a diferença
 * entre as duas parece contradição.
 */

const KM = new Intl.NumberFormat("pt-BR", {
  minimumFractionDigits: 0,
  maximumFractionDigits: 3,
});

/** "8 de agosto" como se lê, a partir de `YYYY-MM-DD`. */
function dataLegivel(valor: string): string {
  const [ano, mes, dia] = valor.slice(0, 10).split("-");
  if (!ano || !mes || !dia) return valor.slice(0, 10);
  return `${dia}/${mes}/${ano}`;
}

/**
 * O período dos apontamentos, dito do jeito mais curto que ainda é exato.
 *
 * <p>Um dia só não vira intervalo: "de 08/08 a 08/08" faz o leitor procurar a
 * diferença entre as duas datas que ele acabou de ler.
 */
function quando(aviso: ApontamentosSemLinha): string {
  const primeira = aviso.primeiraData ? dataLegivel(aviso.primeiraData) : "";
  const ultima = aviso.ultimaData ? dataLegivel(aviso.ultimaData) : "";
  if (!primeira && !ultima) return "";
  if (!ultima || primeira === ultima) return ` do RDO de ${primeira}`;
  if (!primeira) return ` do RDO de ${ultima}`;
  return ` de RDOs entre ${primeira} e ${ultima}`;
}

function faixa(inicial: number, final: number): string {
  const menor = Math.min(inicial, final);
  const maior = Math.max(inicial, final);
  return menor === maior
    ? `km ${KM.format(menor)}`
    : `km ${KM.format(menor)} ao ${KM.format(maior)}`;
}

export interface AvisoDoQueEsperaARegua {
  titulo: string;
  detalhe: string;
  /** Verdadeiro quando cadastrar o eixo é o gesto que resolve. */
  pedeOEixo: boolean;
}

export function avisoDoQueEsperaARegua(
  aviso: ApontamentosSemLinha | undefined,
): AvisoDoQueEsperaARegua | null {
  if (!aviso || aviso.total <= 0) return null;

  const quantos =
    aviso.total === 1
      ? "1 trecho apontado por quilômetro"
      : `${aviso.total} trechos apontados por quilômetro`;
  const onde = faixa(aviso.kmInicial, aviso.kmFinal);

  if (aviso.motivo === "SEM_EIXO") {
    return {
      titulo: `${quantos} espera a régua da obra.`,
      detalhe:
        `O ${onde}${quando(aviso)} está declarado e não tem sobre o que se` +
        " apoiar: esta obra ainda não tem o eixo cadastrado. Cadastre o eixo" +
        " da rodovia uma vez e todo quilômetro apontado vira linha no mapa," +
        " na data em que foi apontado.",
      pedeOEixo: true,
    };
  }

  const doEixo =
    aviso.eixoKmInicial !== null && aviso.eixoKmFinal !== null
      ? faixa(aviso.eixoKmInicial, aviso.eixoKmFinal)
      : "";
  return {
    titulo: `${quantos} ficou fora do eixo cadastrado.`,
    detalhe:
      `O ${onde}${quando(aviso)} está declarado no RDO` +
      (doEixo ? `, e o eixo desta obra cobre do ${doEixo}` : "") +
      ". Estenda o eixo até esse trecho, ou corrija o quilômetro no RDO —" +
      " o mapa acompanha o documento na leitura seguinte.",
    pedeOEixo: false,
  };
}
