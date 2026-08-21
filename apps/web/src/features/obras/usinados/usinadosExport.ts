import type { ObraUsinados } from "./usinadosApi";

/**
 * O CSV que o Excel brasileiro abre certo no primeiro clique.
 *
 * Separador ponto e vírgula e decimal com vírgula, porque é assim que o
 * Excel pt-BR lê; o BOM na frente é o que o faz reconhecer UTF-8 e não
 * estragar o "³" de M³. Célula ausente fica vazia — vazio é "não declarado",
 * e escrever 0 afirmaria uma medição que não aconteceu.
 */
export interface UsinadosExportResult {
  filename: string;
  content: string;
}

const BOM = "\ufeff";
const SEPARADOR = ";";

/**
 * Teto de c\u00e9lula do Excel: 32.767 caracteres. Nenhum campo nosso chega perto
 * \u2014 material \u00e9 varchar(255) \u2014, mas um export n\u00e3o pode nascer confiando nisso:
 * foi exatamente um limite de caracteres estourado que j\u00e1 quebrou exporta\u00e7\u00e3o
 * neste sistema. Corta com retic\u00eancia, nunca corrompe o arquivo.
 */
const LIMITE_CELULA_EXCEL = 32_000;

/** Windows limita o caminho completo; o nome do arquivo fica bem aqu\u00e9m. */
const LIMITE_SLUG_DA_OBRA = 48;

const CABECALHO = [
  "Material",
  "Unidade",
  "Previsto",
  "Usinado",
  "Aplicado (feito)",
  "Não aplicado",
  "Sobra (desperdiçado)",
  "RDOs",
  "Primeira data",
  "Última data",
  "Preço unitário (R$)",
  "Valor aplicado (R$)",
  "Valor desperdiçado (R$)",
  "Observação de preço",
];

function celulaTexto(valor: string | null): string {
  if (!valor) return "";
  let texto = valor.length > LIMITE_CELULA_EXCEL
    ? `${valor.slice(0, LIMITE_CELULA_EXCEL)}…`
    : valor;
  // Texto que começa como fórmula (=, +, -, @) executaria ao abrir no Excel.
  // O apóstrofo na frente é a marca de "isto é texto" da própria planilha.
  if (/^[=+\-@]/.test(texto)) {
    texto = `'${texto}`;
  }
  const precisaAspas = /[";\n\r]/.test(texto);
  return precisaAspas ? `"${texto.replaceAll('"', '""')}"` : texto;
}

function celulaNumero(valor: number | null): string {
  if (valor === null) return "";
  // O número viaja com vírgula decimal e sem separador de milhar: milhar
  // pontuado vira texto no Excel, e é número que a planilha precisa somar.
  return String(valor).replace(".", ",");
}

function observacaoDePreco(
  motivo: "SEM_PRECO" | "PRECO_AMBIGUO" | null,
  precosVisiveis: boolean,
): string {
  if (!precosVisiveis) return "financeiro não visível para este acesso";
  if (motivo === "SEM_PRECO") return "sem preço no catálogo da obra";
  if (motivo === "PRECO_AMBIGUO") {
    return "mais de um preço vigente para este nome e unidade";
  }
  return "";
}

export function buildUsinadosCsv(
  usinados: ObraUsinados,
  generatedAt: string = new Date().toISOString(),
): UsinadosExportResult {
  const linhas = [CABECALHO.join(SEPARADOR)];
  for (const material of usinados.materiais) {
    linhas.push([
      celulaTexto(material.material),
      celulaTexto(material.unidade),
      celulaNumero(material.quantidadePrevista),
      celulaNumero(material.quantidadeUsinada),
      celulaNumero(material.quantidadeAplicada),
      celulaNumero(material.quantidadeNaoAplicada),
      celulaNumero(material.quantidadeSobra),
      String(material.totalRdos),
      celulaTexto(material.primeiraData),
      celulaTexto(material.ultimaData),
      celulaNumero(material.precoUnitario),
      celulaNumero(material.valorAplicado),
      celulaNumero(material.valorDesperdicado),
      celulaTexto(
        observacaoDePreco(material.precoMotivo, usinados.precosVisiveis),
      ),
    ].join(SEPARADOR));
  }
  if (usinados.totais) {
    linhas.push([
      celulaTexto("TOTAL (linhas com preço)"),
      "", "", "", "", "", "", "", "", "", "",
      celulaNumero(usinados.totais.valorAplicado),
      celulaNumero(usinados.totais.valorDesperdicado),
      usinados.totais.materiaisSemPreco > 0
        ? celulaTexto(
            `${usinados.totais.materiaisSemPreco} material(is) sem preço fora do total`,
          )
        : "",
    ].join(SEPARADOR));
  }

  const dia = generatedAt.slice(0, 10);
  // O nome da obra vira um slug curto: sem acento, sem separador ex\u00f3tico e
  // com teto de tamanho \u2014 nome de arquivo comprido demais quebra no Windows
  // antes de o download terminar.
  const nomeDaObra = (usinados.obraNome
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^A-Za-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .toLowerCase() || "obra")
    .slice(0, LIMITE_SLUG_DA_OBRA)
    .replace(/-+$/, "");
  return {
    filename: `usinados-${nomeDaObra}-${dia}.csv`,
    content: BOM + linhas.join("\r\n") + "\r\n",
  };
}

/** Entrega o CSV como download, sem sair da tela. */
export function baixarUsinadosCsv(resultado: UsinadosExportResult): void {
  const blob = new Blob([resultado.content], {
    type: "text/csv;charset=utf-8",
  });
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = resultado.filename;
    anchor.rel = "noopener";
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
