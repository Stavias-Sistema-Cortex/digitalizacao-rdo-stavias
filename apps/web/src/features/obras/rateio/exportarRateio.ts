/**
 * O rateio como planilha, para quem precisa levá-lo adiante.
 *
 * <p>O formato é o mesmo da planilha mantida à mão: uma linha por pessoa,
 * uma coluna por dia com o nome da obra daquele dia, e à direita a fatia do
 * período que coube a cada obra. Quem receber o arquivo reconhece o documento
 * — a diferença é que ninguém mais o digita.
 *
 * <p>Separador ponto e vírgula e decimal com vírgula porque é assim que o
 * Excel em português abre um arquivo sem perguntar nada. Com vírgula de
 * separador, cada número decimal viraria duas colunas e a planilha chegaria
 * torta do outro lado. O BOM no começo é o que faz os acentos aparecerem.
 */

import type {
  RateioDeColaborador,
  RateioDoPeriodo,
} from "./rateioDeMaoDeObra";

export interface NomeDaObra {
  id: string;
  nome: string;
}

function celula(valor: string): string {
  const texto = valor ?? "";
  // Ponto e vírgula, aspas e quebra de linha exigem o campo entre aspas; as
  // aspas de dentro dobram. Sem isso, um nome de obra com ponto e vírgula
  // quebraria a linha inteira em silêncio.
  if (/[";\n\r]/.test(texto)) {
    return `"${texto.replace(/"/g, '""')}"`;
  }
  return texto;
}

function numero(valor: number, casas: number): string {
  return valor.toFixed(casas).replace(".", ",");
}

/**
 * O dia de uma pessoa, escrito numa célula só.
 *
 * <p>Dividido entre duas obras, sai com as duas separadas por barra: a
 * planilha antiga escolhia uma e perdia a outra, e o arquivo que sai daqui não
 * repete esse apagamento.
 */
function obraDoDia(
  colaborador: RateioDeColaborador,
  dia: string,
  nomePorObra: ReadonlyMap<string, string>,
): string {
  const registro = colaborador.dias.get(dia);
  if (!registro) return "";
  return registro.obraIds
    .map((obraId) => nomePorObra.get(obraId) ?? obraId)
    .join(" / ");
}

export function montarCsvDoRateio(
  rateio: RateioDoPeriodo,
  dias: readonly string[],
  obras: readonly NomeDaObra[],
): string {
  const nomePorObra = new Map(obras.map((obra) => [obra.id, obra.nome]));
  // As colunas de obra saem na ordem em que a tela as mostra, e só as que
  // apareceram no período: uma coluna de obra parada o mês inteiro seria uma
  // coluna de zeros.
  const obrasDoPeriodo = rateio.obraIds;

  const cabecalho = [
    "Mão de obra",
    "Função",
    "Encarregado",
    "Dias apontados",
    ...dias.map((dia) => diaLegivel(dia)),
    ...obrasDoPeriodo.map((obraId) => nomePorObra.get(obraId) ?? obraId),
    "Total",
  ];

  const linhas = [cabecalho.map(celula).join(";")];

  for (const colaborador of rateio.colaboradores) {
    const fatias = obrasDoPeriodo.map((obraId) =>
      numero(colaborador.fracaoPorObra.get(obraId) ?? 0, 6),
    );
    linhas.push(
      [
        celula(colaborador.nome),
        celula(colaborador.funcao),
        celula(colaborador.encarregado),
        String(colaborador.diasApontados),
        ...dias.map((dia) =>
          celula(obraDoDia(colaborador, dia, nomePorObra)),
        ),
        ...fatias,
        colaborador.diasApontados > 0 ? "1,000000" : "0,000000",
      ].join(";"),
    );
  }

  return `\uFEFF${linhas.join("\r\n")}\r\n`;
}

/** `2026-07-05` vira `05/07`, que é como a coluna de um dia se lê. */
function diaLegivel(dia: string): string {
  const [, mes, numeroDoDia] = dia.split("-");
  return numeroDoDia && mes ? `${numeroDoDia}/${mes}` : dia;
}

/** O nome do arquivo diz o que ele é e de quando. */
export function nomeDoArquivoDoRateio(inicio: string, fim: string): string {
  return `rateio-mao-de-obra-${inicio}-a-${fim}.csv`;
}

/**
 * Entrega o arquivo ao navegador.
 *
 * <p>Fica isolado do resto para que a montagem do conteúdo possa ser conferida
 * em teste sem precisar de DOM nem de download.
 */
export function baixarCsvDoRateio(conteudo: string, nomeDoArquivo: string): void {
  const blob = new Blob([conteudo], {
    type: "text/csv;charset=utf-8",
  });
  const url = URL.createObjectURL(blob);
  try {
    const ancora = document.createElement("a");
    ancora.href = url;
    ancora.download = nomeDoArquivo;
    ancora.rel = "noopener";
    ancora.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
