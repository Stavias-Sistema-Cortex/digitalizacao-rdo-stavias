/**
 * A forma canônica do código de uma função operacional.
 *
 * <p>O servidor normaliza antes de gravar — maiúsculas, hífen e espaço viram
 * sublinhado — e recusa o que sobrar fora de letras, números e sublinhado.
 * Repetir a regra aqui não é duplicá-la por acaso: é o que permite mostrar o
 * código exatamente como ele será gravado enquanto a pessoa digita, em vez de
 * ela descobrir a transformação depois de salvar. A recusa continua sendo do
 * servidor; o que muda é que ela deixa de ser a primeira notícia.
 */

/** Como o servidor gravaria o que foi digitado. */
export function normalizarCodigoDeFuncao(valor: string): string {
  return valor
    .trim()
    .toUpperCase()
    .replaceAll("-", "_")
    .replaceAll(" ", "_");
}

/**
 * O que impede o código de ser aceito, ou null quando ele serve.
 *
 * <p>Devolve a frase pronta porque o motivo é a informação útil: "inválido"
 * sozinho manda adivinhar qual das regras foi quebrada.
 */
export function problemaNoCodigoDeFuncao(valor: string): string | null {
  const normalizado = normalizarCodigoDeFuncao(valor);
  if (!normalizado) {
    return "Informe o código da função.";
  }
  if (normalizado.length > 80) {
    return "O código pode ter no máximo 80 caracteres.";
  }
  if (!/^[A-Z0-9_]+$/.test(normalizado)) {
    return "O código aceita apenas letras sem acento, números e sublinhado.";
  }
  return null;
}
