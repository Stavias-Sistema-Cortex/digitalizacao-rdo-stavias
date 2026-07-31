import type { SegmentoTrecho } from "./trechoGeometry";
import { quilometroDeTexto } from "./trechoLocal";

/**
 * Trecho declarado à mão sobre o mapa.
 *
 * O esquemático nasceu lendo só o que o RDO produz, e isso deixa de fora o
 * planejamento: a interdição combinada com a concessionária existe antes de
 * qualquer apontamento. Aqui a pessoa declara o trecho — rodovia, sentido,
 * faixa interditada e quilometragem — e ele passa a conviver com o que o RDO
 * apura, sem substituir nem ser substituído.
 *
 * As chaves gravadas são exatamente as que a política de payload da ontologia
 * já autoriza para geometria (`rodovia`, `sentido`, `faixa`, `kmInicial`,
 * `kmFinal`, `extensaoM`): a permissão existia e nunca fora usada, então o
 * desenho subia sem dizer o que representava.
 */
export interface CadastroTrecho {
  rodovia: string;
  sentido: string;
  faixa: string;
  kmInicial: string;
  kmFinal: string;
  /** Extensão medida em campo. Vazio deixa valer a medida da própria linha. */
  extensaoM: string;
  status: StatusTrechoCadastrado;
}

export type StatusTrechoCadastrado =
  | "PENDENTE"
  | "EM_EXECUCAO"
  | "CONCLUIDO";

export const STATUS_DO_TRECHO: ReadonlyArray<{
  valor: StatusTrechoCadastrado;
  rotulo: string;
}> = [
  { valor: "PENDENTE", rotulo: "Pendente" },
  { valor: "EM_EXECUCAO", rotulo: "Em execução" },
  { valor: "CONCLUIDO", rotulo: "Concluído" },
];

/**
 * Faixa interditada, no vocabulário de quem sinaliza a pista.
 *
 * "Ambas" não é uma faixa: é a declaração de que as duas estão interditadas, e
 * vira dois blocos no esquemático em vez de um rótulo que ninguém sabe ler.
 */
export const FAIXAS_INTERDITAVEIS: ReadonlyArray<{
  valor: string;
  rotulo: string;
}> = [
  { valor: "DIREITA", rotulo: "Direita (lenta)" },
  { valor: "ESQUERDA", rotulo: "Esquerda (rápida)" },
  { valor: "AMBAS", rotulo: "Ambas as faixas" },
];

export const CADASTRO_VAZIO: CadastroTrecho = {
  rodovia: "",
  sentido: "",
  faixa: "",
  kmInicial: "",
  kmFinal: "",
  extensaoM: "",
  status: "PENDENTE",
};

function limpo(valor: string): string | null {
  const texto = valor.trim();
  return texto ? texto : null;
}

function numeroPositivo(valor: string): number | null {
  const texto = valor.trim().replace(",", ".");
  if (!texto) return null;
  const numero = Number(texto);
  return Number.isFinite(numero) && numero > 0 ? numero : null;
}

export function validarCadastro(cadastro: CadastroTrecho): string | null {
  if (!limpo(cadastro.rodovia)) {
    return "Informe a rodovia do trecho.";
  }
  const inicial = quilometroDeTexto(cadastro.kmInicial);
  const final = quilometroDeTexto(cadastro.kmFinal);
  if (inicial === null || final === null) {
    // Um extremo isolado é ponto, não trecho: sem os dois o bloco não tem onde
    // ser desenhado e o lançamento sairia invisível no esquemático.
    return "Informe o km inicial e o km final para o trecho ser posicionável.";
  }
  if (inicial === final) {
    return "O km inicial e o km final não podem ser o mesmo ponto.";
  }
  if (cadastro.extensaoM.trim() && numeroPositivo(cadastro.extensaoM) === null) {
    return "A extensão medida precisa ser um número de metros maior que zero.";
  }
  return null;
}

/**
 * Propriedades gravadas na geometria.
 *
 * Só entram campos declarados: um km em branco não vira zero, porque km 0 é
 * marcação válida e inventá-lo posicionaria o trecho no lugar errado.
 */
export function propriedadesDoCadastro(
  cadastro: CadastroTrecho,
  extensaoDaLinhaM: number | null,
): Record<string, unknown> {
  const propriedades: Record<string, unknown> = {
    nome: nomeDoTrecho(cadastro),
    status: cadastro.status,
  };
  const rodovia = limpo(cadastro.rodovia);
  if (rodovia) propriedades.rodovia = rodovia;
  const sentido = limpo(cadastro.sentido);
  if (sentido) propriedades.sentido = sentido;
  const faixa = limpo(cadastro.faixa);
  if (faixa) propriedades.faixa = faixa;

  const inicial = quilometroDeTexto(cadastro.kmInicial);
  const final = quilometroDeTexto(cadastro.kmFinal);
  if (inicial !== null) propriedades.kmInicial = inicial;
  if (final !== null) propriedades.kmFinal = final;

  // A extensão digitada é medida de campo e prevalece; sem ela vale o
  // comprimento da própria linha desenhada, que é geometria, não estimativa.
  const medida = numeroPositivo(cadastro.extensaoM) ?? extensaoDaLinhaM;
  if (medida !== null && Number.isFinite(medida)) {
    propriedades.extensaoM = Math.round(medida);
  }
  return propriedades;
}

export function nomeDoTrecho(cadastro: CadastroTrecho): string {
  const rodovia = limpo(cadastro.rodovia) ?? "Trecho";
  const inicial = quilometroDeTexto(cadastro.kmInicial);
  const final = quilometroDeTexto(cadastro.kmFinal);
  if (inicial === null || final === null) {
    return rodovia;
  }
  return `${rodovia} · km ${inicial} a ${final}`;
}

function textoDaPropriedade(
  propriedades: Record<string, unknown>,
  chave: string,
): string | null {
  const valor = propriedades[chave];
  return typeof valor === "string" && valor.trim() ? valor.trim() : null;
}

function numeroDaPropriedade(
  propriedades: Record<string, unknown>,
  chave: string,
): number | null {
  const valor = propriedades[chave];
  return typeof valor === "number" && Number.isFinite(valor) ? valor : null;
}

/**
 * Projeta um trecho cadastrado nos segmentos do esquemático.
 *
 * "Ambas as faixas" vira dois segmentos, um por faixa: a interdição realmente
 * ocupa as duas, e um bloco só deixaria metade da pista sem marcação.
 */
export function segmentosDoTrechoCadastrado(input: {
  id: string;
  propriedades: Record<string, unknown>;
  validoDesde: string | null;
  procedencia: SegmentoTrecho["procedencia"];
}): SegmentoTrecho[] {
  const kmInicial = numeroDaPropriedade(input.propriedades, "kmInicial");
  const kmFinal = numeroDaPropriedade(input.propriedades, "kmFinal");
  if (kmInicial === null || kmFinal === null) {
    return [];
  }
  const faixaDeclarada = textoDaPropriedade(input.propriedades, "faixa");
  const faixas = faixaDeclarada === "AMBAS"
    ? ["DIREITA", "ESQUERDA"]
    : [faixaDeclarada];

  return faixas.map((faixa, indice) => ({
    id: faixas.length > 1 ? `${input.id}:${faixa}` : input.id,
    origem: "CADASTRO_MAPA" as const,
    rdoId: null,
    numeroRdo: null,
    data: input.validoDesde?.slice(0, 10) ?? null,
    servicoNome: textoDaPropriedade(input.propriedades, "nome"),
    subtrecho: null,
    sentido: textoDaPropriedade(input.propriedades, "sentido"),
    pista: null,
    faixa,
    kmInicial,
    kmFinal,
    estacaInicial: null,
    estacaFinal: null,
    // A extensão é a do trecho inteiro; reparti-la entre as faixas inventaria
    // uma medição por faixa que ninguém fez.
    extensaoM: indice === 0
      ? numeroDaPropriedade(input.propriedades, "extensaoM")
      : null,
    larguraM: null,
    areaM2: null,
    massaTonelada: null,
    status: textoDaPropriedade(input.propriedades, "status"),
    rdoStatus: null,
    procedencia: input.procedencia,
    pistaInferida: false,
  }));
}

export interface DivergenciaComORdo {
  campo: string;
  cadastrado: string;
  apurado: string;
  numeroRdo: string | null;
}

function sobrepoeEmKm(
  esquerda: { kmInicial: number; kmFinal: number },
  direita: { kmInicial: number | null; kmFinal: number | null },
): boolean {
  if (direita.kmInicial === null || direita.kmFinal === null) return false;
  const inicioA = Math.min(esquerda.kmInicial, esquerda.kmFinal);
  const fimA = Math.max(esquerda.kmInicial, esquerda.kmFinal);
  const inicioB = Math.min(direita.kmInicial, direita.kmFinal);
  const fimB = Math.max(direita.kmInicial, direita.kmFinal);
  return inicioA <= fimB && inicioB <= fimA;
}

/**
 * Compara o trecho declarado com o que o RDO apurou no mesmo pedaço da pista.
 *
 * Nenhum dos dois é corrigido: o cadastro é o que foi combinado e o RDO é o que
 * foi executado, e a diferença entre eles costuma ser a informação que
 * interessa. O que o sistema deve fazer é dizer que discordam, para quem está
 * em campo decidir — e não escolher em silêncio um dos lados.
 */
export function divergenciasComORdo(
  cadastro: CadastroTrecho,
  segmentosDoRdo: readonly SegmentoTrecho[],
): DivergenciaComORdo[] {
  const kmInicial = quilometroDeTexto(cadastro.kmInicial);
  const kmFinal = quilometroDeTexto(cadastro.kmFinal);
  if (kmInicial === null || kmFinal === null) {
    return [];
  }
  const declarado = { kmInicial, kmFinal };
  const sobrepostos = segmentosDoRdo.filter(
    (segmento) =>
      segmento.origem !== "PROGRAMACAO" && sobrepoeEmKm(declarado, segmento),
  );
  if (sobrepostos.length === 0) {
    return [];
  }

  const divergencias: DivergenciaComORdo[] = [];
  const faixaDeclarada = limpo(cadastro.faixa);
  const sentidoDeclarado = limpo(cadastro.sentido);

  for (const segmento of sobrepostos) {
    const faixaApurada = segmento.faixa;
    if (
      faixaDeclarada &&
      faixaDeclarada !== "AMBAS" &&
      faixaApurada &&
      faixaApurada.toUpperCase() !== faixaDeclarada.toUpperCase()
    ) {
      divergencias.push({
        campo: "Faixa",
        cadastrado: faixaDeclarada,
        apurado: faixaApurada,
        numeroRdo: segmento.numeroRdo,
      });
    }
    const sentidoApurado = segmento.sentido ?? segmento.pista;
    if (
      sentidoDeclarado &&
      sentidoApurado &&
      sentidoApurado.toUpperCase() !== sentidoDeclarado.toUpperCase()
    ) {
      divergencias.push({
        campo: "Sentido",
        cadastrado: sentidoDeclarado,
        apurado: sentidoApurado,
        numeroRdo: segmento.numeroRdo,
      });
    }
  }

  // Uma divergência por campo basta: repetir a mesma diferença para cada RDO
  // que a contém vira ruído e esconde as demais.
  const vistas = new Set<string>();
  return divergencias.filter((item) => {
    const chave = `${item.campo}:${item.cadastrado}:${item.apurado}`;
    if (vistas.has(chave)) return false;
    vistas.add(chave);
    return true;
  });
}
