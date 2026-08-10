/**
 * A leitura de quilômetro, que é parecida com a dos outros números e não é a
 * mesma.
 *
 * <p>Todo campo digitado no Córtex passa por `decimalDigitado`, que lê ponto de
 * milhar como português manda: `1.234` é mil duzentos e trinta e quatro. Para
 * quilômetro essa regra é exatamente ao contrário do que se quer — `206.822` é
 * o km 206,822, a notação que a base inteira já guarda em `varchar`, e lê-la
 * como milhar joga o trecho mil vezes para frente na rodovia. Foi o que
 * acontecia: a extensão do RDO saía em centenas de milhares de metros, e o
 * trecho desenhado ia parar fora do mapa.
 *
 * <p>Então aqui um ponto sozinho é sempre decimal. Quando os dois separadores
 * aparecem, o último a aparecer é o decimal e o outro é milhar — é o que
 * distingue `1.206,5` de `1,206.5`. E a notação rodoviária `309+400`
 * (quilômetro mais metros) continua valendo.
 *
 * <p>Espelha `QuilometroParser` do servidor. Texto irreconhecível devolve
 * `null`, nunca zero: km 0 é uma marcação real.
 */

import { decimalSemMilhar } from "./numeroDigitado";

const KM_MAIS_METROS = /^(\d{1,4})\+(\d{1,3})$/;
const DECIMAL = /^\d{1,4}(?:\.\d{1,3})?$/;

/**
 * O quilômetro que o texto marca, ou `null` quando ele não marca nenhum.
 *
 * <p>Aceita `"206,822"`, `"206.822"`, `"1.206,5"`, `"km 206"` e `"309+400"`.
 */
export function quilometroDigitado(bruto: unknown): number | null {
  if (typeof bruto !== "string" && typeof bruto !== "number") {
    return null;
  }
  const normalizado = String(bruto)
    .trim()
    .toUpperCase()
    .replaceAll("KM", "")
    .replaceAll(" ", "");
  if (!normalizado) {
    return null;
  }
  const kmMaisMetros = KM_MAIS_METROS.exec(normalizado);
  if (kmMaisMetros) {
    return Number(kmMaisMetros[1]) + Number(kmMaisMetros[2]) / 1000;
  }
  const decimal = decimalSemMilhar(normalizado);
  return decimal !== null && DECIMAL.test(decimal) ? Number(decimal) : null;
}
