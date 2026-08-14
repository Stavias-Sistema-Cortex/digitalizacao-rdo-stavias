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

const CARIMBO_CIVIL_SEM_FUSO =
  /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?$/;
const DESIGNADOR_DE_FUSO = /(?:Z|[+-]\d{2}:\d{2})$/i;
const FRACAO_ANTES_DO_FUSO =
  /\.(\d+)(?=(?:Z|[+-]\d{2}:\d{2})$)/i;

const partesCivisEmBrasilia = new Intl.DateTimeFormat("en-CA", {
  calendar: "iso8601",
  numberingSystem: "latn",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hourCycle: "h23",
  timeZone: FUSO_BRASILIA,
});

/**
 * Lê um carimbo UTC sem offset como o instante que ele de fato é.
 *
 * <p>Alguns contratos legados produzem UTC — por exemplo, mensagens usava
 * `LocalDateTime.now(ZoneOffset.UTC)` — mas serializam como `LocalDateTime`,
 * que não tem fuso: o JSON sai
 * `2026-08-14T12:03:00`, sem o `Z`. Pela especificação do ECMAScript, data e
 * hora sem designador de fuso é lida como hora <em>local</em>, então
 * `new Date` devolvia instantes diferentes conforme a máquina — e num aparelho
 * em Brasília o erro de leitura cancelava o acerto da exibição, mostrando o
 * relógio de UTC como se fosse o daqui.
 *
 * <p>Use esta função somente quando o campo é semanticamente UTC. A normalização
 * fica na fronteira, e não em cada tela: além do que chega
 * agora pela API, o aparelho já tem carimbos nesse formato gravados offline, e
 * eles precisam ser lidos do mesmo jeito. Corrigir o contrato para
 * `Instant`/`OffsetDateTime` com `Z` continua valendo — mas não dispensaria
 * esta função enquanto houver registro antigo no armazenamento local.
 *
 * <p>Data pura é recusada de propósito: `2026-08-14` não representa um
 * instante. Quem precisa exibi-la deve formatar seus componentes civis, sem
 * passar por `Date` e correr o risco de cair no dia anterior em Brasília.
 */
export function instanteDoServidor(value: string | number | Date): Date {
  if (value instanceof Date) return value;
  if (typeof value === "number") return new Date(value);
  const texto = value.trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(texto)) {
    return new Date(Number.NaN);
  }
  return new Date(
    SEM_DESIGNADOR_DE_FUSO.test(texto)
      ? `${texto.replace(" ", "T")}Z`
      : texto,
  );
}

/** Compara carimbos pelo instante, inclusive quando a precisão ISO difere. */
export function compararInstantesDoServidor(
  left: string,
  right: string,
): number {
  const leftKey = chaveDoInstanteDoServidor(left);
  const rightKey = chaveDoInstanteDoServidor(right);
  if (leftKey && rightKey) {
    if (leftKey.second !== rightKey.second) {
      return leftKey.second - rightKey.second;
    }
    if (leftKey.fraction === rightKey.fraction) return 0;
    return leftKey.fraction < rightKey.fraction ? -1 : 1;
  }
  return left.localeCompare(right);
}

function chaveDoInstanteDoServidor(
  value: string,
): { second: number; fraction: string } | null {
  const instant = instanteDoServidor(value);
  const milliseconds = instant.getTime();
  if (Number.isNaN(milliseconds)) return null;

  const text = value.trim();
  const civil = CARIMBO_CIVIL_SEM_FUSO.exec(text);
  const rawFraction =
    civil?.[7] ?? FRACAO_ANTES_DO_FUSO.exec(text)?.[1] ?? "";
  return {
    second: Math.floor(milliseconds / 1_000),
    // Java Instant e PostgreSQL chegam, no máximo, a nanossegundos.
    fraction: rawFraction.slice(0, 9).padEnd(9, "0"),
  };
}

/**
 * Compara o contrato misto de Obra pelo relógio civil mostrado em Brasília.
 *
 * <p>Sem offset, preserva os componentes do legado. Com `Z`/offset, converte
 * o instante para os componentes civis de Brasília. A fração original fica
 * fora de `Date`, que guarda somente milissegundos, para não empatar `.123`
 * com `.123456`.
 */
export function compararCarimbosEmBrasilia(
  left: string,
  right: string,
): number {
  const leftKey = chaveCivilEmBrasilia(left);
  const rightKey = chaveCivilEmBrasilia(right);
  if (!leftKey || !rightKey) {
    return left.localeCompare(right);
  }
  if (leftKey.base !== rightKey.base) {
    return leftKey.base < rightKey.base ? -1 : 1;
  }
  const precision = Math.max(
    leftKey.fraction.length,
    rightKey.fraction.length,
  );
  const leftFraction = leftKey.fraction.padEnd(precision, "0");
  const rightFraction = rightKey.fraction.padEnd(precision, "0");
  if (leftFraction === rightFraction) return 0;
  return leftFraction < rightFraction ? -1 : 1;
}

function chaveCivilEmBrasilia(
  value: string,
): { base: string; fraction: string } | null {
  const texto = value.trim();
  const civil = CARIMBO_CIVIL_SEM_FUSO.exec(texto);
  if (civil) {
    const parsed = new Date(`${texto.replace(" ", "T")}Z`);
    if (Number.isNaN(parsed.getTime())) return null;
    const [, year, month, day, hour, minute, second = "00", fraction = ""] =
      civil;
    return {
      base: `${year}-${month}-${day}T${hour}:${minute}:${second}`,
      fraction,
    };
  }
  if (!DESIGNADOR_DE_FUSO.test(texto)) return null;
  const instant = new Date(texto);
  if (Number.isNaN(instant.getTime())) return null;
  const parts = partesCivisEmBrasilia.formatToParts(instant);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? "";
  return {
    base: `${part("year")}-${part("month")}-${part("day")}T${part("hour")}:${part("minute")}:${part("second")}`,
    fraction: FRACAO_ANTES_DO_FUSO.exec(texto)?.[1] ?? "",
  };
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

/**
 * Formata um carimbo cujo contrato pode ser antigo sem corromper seu sentido.
 *
 * <p>Com `Z`/offset, o valor é um instante e é convertido para Brasília. Sem
 * offset, ele é um relógio civil legado e seus componentes são preservados.
 * Essa função existe para superfícies que misturam as duas gerações do
 * contrato (como a timeline operacional); campos conhecidos como UTC devem
 * continuar usando {@link formatarEmBrasilia}.
 */
export function formatarCarimboEmBrasilia(
  value: string | number | Date,
  options: Intl.DateTimeFormatOptions,
): string | null {
  if (typeof value !== "string") {
    return formatarEmBrasilia(value, options);
  }

  const texto = value.trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(texto)) {
    return null;
  }

  if (SEM_DESIGNADOR_DE_FUSO.test(texto)) {
    const civil = new Date(`${texto.replace(" ", "T")}Z`);
    if (Number.isNaN(civil.getTime())) {
      return null;
    }
    return new Intl.DateTimeFormat("pt-BR", {
      ...options,
      timeZone: "UTC",
    }).format(civil);
  }

  return formatarEmBrasilia(texto, options);
}

/** Retorna o dia civil corrente da operação, independentemente do aparelho. */
export function dataHojeEmBrasilia(agora: Date = new Date()): string {
  const partes = new Intl.DateTimeFormat("pt-BR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone: FUSO_BRASILIA,
  }).formatToParts(agora);
  const parte = (tipo: Intl.DateTimeFormatPartTypes) =>
    partes.find((item) => item.type === tipo)?.value ?? "";
  return `${parte("year")}-${parte("month")}-${parte("day")}`;
}
