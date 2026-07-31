/**
 * Preenchimento assistido do catálogo de serviços.
 *
 * O catálogo é digitado por quem está fechando medição, não por quem conhece a
 * convenção interna de códigos. Estas funções deixam a digitação livre e
 * resolvem o resto: reconhecem o serviço que já existe antes de criar um
 * duplicado, propõem um código a partir do nome e escrevem a unidade no
 * símbolo correto.
 *
 * São puras de propósito — a regra de reconhecimento é a mesma do RDO e
 * precisa ser conferível sem montar tela nem banco.
 */

/** Mesma normalização de busca do RDO: sem acento, minúscula, sem borda. */
export function textoDeBusca(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

/**
 * Unidades escritas do jeito que se digita, mapeadas para o símbolo.
 *
 * A chave é o texto já normalizado (sem acento, minúsculo, sem espaço nem
 * ponto), de forma que "M2", "m2", "m²" e "m 2" cheguem todos ao mesmo lugar.
 */
const UNIDADE_CANONICA: ReadonlyMap<string, string> = new Map([
  ["m2", "M²"],
  ["m²", "M²"],
  ["metroquadrado", "M²"],
  ["metrosquadrados", "M²"],
  ["m3", "M³"],
  ["m³", "M³"],
  ["metrocubico", "M³"],
  ["metroscubicos", "M³"],
  ["m", "M"],
  ["ml", "M"],
  ["metro", "M"],
  ["metrolinear", "M"],
  ["metroslineares", "M"],
  ["km", "KM"],
  ["quilometro", "KM"],
  ["t", "T"],
  ["ton", "T"],
  ["tonelada", "T"],
  ["toneladas", "T"],
  ["kg", "KG"],
  ["quilo", "KG"],
  ["l", "L"],
  ["litro", "L"],
  ["h", "H"],
  ["hora", "H"],
  ["horas", "H"],
  ["un", "UN"],
  ["und", "UN"],
  ["unid", "UN"],
  ["unidade", "UN"],
  ["vb", "VB"],
  ["verbas", "VB"],
]);

/**
 * Escreve a unidade no símbolo correto.
 *
 * O que não está na tabela passa direto em maiúsculas, para nunca bloquear uma
 * unidade legítima que ainda não foi mapeada — a lista é uma conveniência, não
 * uma restrição.
 */
export function normalizarUnidade(value: string): string {
  const bruto = value.trim();
  if (!bruto) return "";
  const chave = textoDeBusca(bruto).replace(/[\s.]/g, "");
  return UNIDADE_CANONICA.get(chave) ?? bruto.toUpperCase();
}

export interface ServicoConhecido {
  id: string;
  code: string;
  name: string;
}

/**
 * Reconhece o serviço que a pessoa acabou de escrever.
 *
 * Quem digita "fresagem" tendo "Fresagem" no catálogo quer aquele serviço, não
 * um segundo registro com o mesmo nome. Casa por código ou por nome, ignorando
 * acento e caixa; devolve nulo quando não há correspondência exata, que é o
 * caso legítimo de serviço novo.
 */
export function reconhecerServico<T extends ServicoConhecido>(
  catalogo: readonly T[],
  texto: string,
): T | null {
  const alvo = textoDeBusca(texto);
  if (!alvo) return null;
  return (
    catalogo.find((servico) => textoDeBusca(servico.code) === alvo) ??
    catalogo.find((servico) => textoDeBusca(servico.name) === alvo) ??
    null
  );
}

const CODIGO_MAXIMO = 80;

/**
 * Propõe um código a partir do nome do serviço.
 *
 * O código continua editável: isto é um ponto de partida para quem não tem a
 * convenção na cabeça, não uma imposição. Só é sugerido enquanto o campo
 * estiver intocado.
 */
export function sugerirCodigoDoServico(
  nome: string,
  catalogo: readonly ServicoConhecido[] = [],
): string {
  const base = textoDeBusca(nome)
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .toUpperCase()
    .slice(0, CODIGO_MAXIMO);
  if (!base) return "";

  const ocupados = new Set(
    catalogo.map((servico) => servico.code.trim().toUpperCase()),
  );
  if (!ocupados.has(base)) return base;

  for (let sufixo = 2; sufixo < 1000; sufixo += 1) {
    const marca = `-${sufixo}`;
    const candidato =
      `${base.slice(0, CODIGO_MAXIMO - marca.length)}${marca}`;
    if (!ocupados.has(candidato)) return candidato;
  }
  return base;
}
