/**
 * O relógio do Córtex é o de Brasília, e não o do aparelho.
 *
 * <p>Um RDO pertence a um dia de obra, e a obra fica no Brasil. Quando a hora
 * exibida seguia o fuso do aparelho, o mesmo instante aparecia diferente em
 * cada máquina: um servidor em UTC mostrava a mensagem das nove da manhã como
 * meio-dia, e a tarja do dia virava antes da meia-noite de quem estava em
 * campo. Fixar o fuso tira essa variável de quem lê o relatório.
 *
 * <p>Vale para <em>instante</em> — o que veio do servidor como data e hora, tipo
 * `2026-08-14T12:03:00Z`. Não vale para <em>data pura</em>, do tipo `dataRdo`
 * ("2026-08-14"), que não tem hora nem fuso: essas são montadas e formatadas no
 * mesmo fuso de propósito, e carimbar Brasília por cima jogaria a data um dia
 * para trás em quem não está no Brasil.
 */
export const FUSO_BRASILIA = "America/Sao_Paulo";

/**
 * Formata um instante no relógio de Brasília.
 *
 * <p>Devolve `null` quando o valor não é uma data válida, para quem chama
 * decidir o texto de ausência — que muda de tela para tela.
 */
export function formatarEmBrasilia(
  value: string | number | Date,
  options: Intl.DateTimeFormatOptions,
): string | null {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return new Intl.DateTimeFormat("pt-BR", {
    ...options,
    timeZone: FUSO_BRASILIA,
  }).format(date);
}
