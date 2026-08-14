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
 * Data e hora sem fuso nenhum ao final — o que a API manda hoje.
 *
 * <p>Casa `2026-08-14T12:03:00`, com ou sem segundos e frações, e aceita o
 * espaço no lugar do T. Não casa quem já traz `Z` ou `-03:00`, nem a data pura
 * `2026-08-14`, que não tem parte de hora.
 */
const SEM_DESIGNADOR_DE_FUSO =
  /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/;

/**
 * Lê um carimbo do servidor como o instante que ele de fato é.
 *
 * <p>O backend produz UTC — `LocalDateTime.now(ZoneOffset.UTC)` — mas serializa
 * como `LocalDateTime`, e `LocalDateTime` não tem fuso: o JSON sai
 * `2026-08-14T12:03:00`, sem o `Z`. Pela especificação do ECMAScript, data e
 * hora sem designador de fuso é lida como hora <em>local</em>, então
 * `new Date` devolvia instantes diferentes conforme a máquina — e num aparelho
 * em Brasília o erro de leitura cancelava o acerto da exibição, mostrando o
 * relógio de UTC como se fosse o daqui.
 *
 * <p>A normalização é aqui, na fronteira, e não em cada tela: além do que chega
 * agora pela API, o aparelho já tem carimbos nesse formato gravados offline, e
 * eles precisam ser lidos do mesmo jeito. Corrigir o contrato para
 * `Instant`/`OffsetDateTime` com `Z` continua valendo — mas não dispensaria
 * esta função enquanto houver registro antigo no armazenamento local.
 *
 * <p>Data pura fica de fora de propósito: `2026-08-14` não casa a expressão, e
 * segue lida como o próprio dia.
 */
export function instanteDoServidor(value: string | number | Date): Date {
  if (value instanceof Date) return value;
  if (typeof value === "number") return new Date(value);
  const texto = value.trim();
  return new Date(
    SEM_DESIGNADOR_DE_FUSO.test(texto)
      ? `${texto.replace(" ", "T")}Z`
      : texto,
  );
}

/**
 * Formata um instante do servidor no relógio de Brasília.
 *
 * <p>Devolve `null` quando o valor não é uma data válida, para quem chama
 * decidir o texto de ausência — que muda de tela para tela.
 */
export function formatarEmBrasilia(
  value: string | number | Date,
  options: Intl.DateTimeFormatOptions,
): string | null {
  const date = instanteDoServidor(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return new Intl.DateTimeFormat("pt-BR", {
    ...options,
    timeZone: FUSO_BRASILIA,
  }).format(date);
}
