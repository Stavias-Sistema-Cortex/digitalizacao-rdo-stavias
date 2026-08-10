/**
 * Ler um número como ele é digitado no Brasil.
 *
 * <p>O Córtex tinha esta conversão espalhada em quinze lugares, sempre na
 * mesma forma: `value.replace(",", ".")`. Ela troca só a <em>primeira</em>
 * vírgula e não sabe nada sobre o ponto de milhar, então errava dos dois
 * jeitos que existem.
 *
 * <p>O primeiro erro era visível e irritante: "1.234,56" virava "1.234.56",
 * que não é número nenhum — o campo recusava um preço escrito corretamente em
 * português, e quem digitava não tinha como adivinhar que o problema era o
 * ponto.
 *
 * <p>O segundo era invisível e caro: "1.234" — mil duzentos e trinta e quatro —
 * passava pela validação como 1,234. Mil vezes menor, sem aviso, gravado.
 * Um preço unitário assim atravessa o catálogo, entra no snapshot da execução
 * e sai do outro lado como receita medida; ninguém confere um número que o
 * sistema aceitou.
 *
 * <p>Por isso a leitura mora aqui, sozinha e conferível: é uma fronteira de
 * dados, e fronteira duplicada envelhece diferente.
 */

/**
 * Agrupamento de milhar em português: um a três dígitos, depois grupos de três.
 *
 * <p>O primeiro grupo não começa em zero de propósito, e é isso que separa
 * "1.500" (mil e quinhentos) de "0.500" (meio). Ninguém escreve o milhar com
 * zero à esquerda, então um zero antes do ponto só pode ser a parte inteira de
 * um decimal.
 */
const MILHAR_EM_PORTUGUES = /^[1-9]\d{0,2}(?:\.\d{3})+$/;

/**
 * Traduz o que foi digitado para a forma que o resto do mundo entende.
 *
 * <p>Devolve o texto com ponto decimal e sem separador de milhar, ou `null`
 * quando não há número ali. Não decide o que fazer com o resultado — quem
 * chama é que sabe se aceita casas demais, se zero vale, se negativo existe.
 *
 * <p>As regras, na ordem em que são aplicadas:
 *
 * <ol>
 *   <li><b>Vírgula e ponto juntos</b> — o último a aparecer é o decimal e o
 *       outro é milhar. Vale para "1.234,56" e para "1,234.56", porque
 *       planilha exportada em inglês chega aqui do mesmo jeito.</li>
 *   <li><b>Só vírgula</b> — é o decimal, sempre. "12,50" é doze e cinquenta;
 *       vírgula não separa milhar em português.</li>
 *   <li><b>Só ponto</b> — o caso ambíguo. "1.234" pode ser mil duzentos e
 *       trinta e quatro ou um vírgula duzentos e trinta e quatro, e nada no
 *       texto resolve. Vence o agrupamento de milhar quando o texto inteiro
 *       tem essa forma; fora dela, o ponto é decimal, que é como "12.50" e
 *       "0.75" precisam continuar sendo lidos.</li>
 * </ol>
 */
export function decimalDigitado(valor: string): string | null {
  const texto = valor.trim().replace(/\s+/g, "");
  if (!texto) return null;

  const sinal = texto.startsWith("-") ? "-" : "";
  const semSinal = sinal ? texto.slice(1) : texto;
  if (!/^[\d.,]+$/.test(semSinal)) return null;

  const ultimaVirgula = semSinal.lastIndexOf(",");
  const ultimoPonto = semSinal.lastIndexOf(".");

  let cru: string;
  if (ultimaVirgula >= 0 && ultimoPonto >= 0) {
    const decimal = ultimaVirgula > ultimoPonto ? "," : ".";
    const milhar = decimal === "," ? "." : ",";
    cru = semSinal.split(milhar).join("").replace(decimal, ".");
  } else if (ultimaVirgula >= 0) {
    cru = semSinal.replace(",", ".");
  } else if (MILHAR_EM_PORTUGUES.test(semSinal)) {
    cru = semSinal.split(".").join("");
  } else {
    cru = semSinal;
  }

  // Sobrou mais de um separador: o texto não descreve um número só.
  if (!/^\d*(?:\.\d*)?$/.test(cru) || !/\d/.test(cru)) return null;
  return `${sinal}${cru}`;
}

/**
 * O mesmo, já como número.
 *
 * <p>Campo em branco devolve `null`, e não zero: ausência de medida não é
 * medida zero, e essa diferença já custou caro em outros pontos do Córtex.
 */
export function numeroDigitado(valor: string | number): number | null {
  if (typeof valor === "number") {
    return Number.isFinite(valor) ? valor : null;
  }
  const texto = decimalDigitado(valor);
  if (texto === null) return null;
  const numero = Number(texto);
  return Number.isFinite(numero) ? numero : null;
}
