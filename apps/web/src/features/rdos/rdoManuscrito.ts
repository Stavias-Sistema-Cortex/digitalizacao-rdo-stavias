import {
  createEmptyEquipamento,
  createEmptyMaoObra,
  createEmptyMaterial,
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "./createEmptyRdo";
import type { CondicaoClimatica, RdoDraft } from "./rdo.types";

/**
 * O RDO de papel lido de volta para dentro do Córtex.
 *
 * <p>O formulário impresso do RDO é fixo: os mesmos cargos, as mesmas
 * máquinas, as mesmas colunas, sempre nas mesmas posições. É isso que torna a
 * leitura possível — não se adivinha o que está escrito à mão, procura-se o
 * rótulo impresso e lê-se o que a pessoa escreveu ao lado dele.
 *
 * <p>Este módulo não reconhece letra: recebe as palavras já reconhecidas, com
 * a posição de cada uma na página, e faz o encaixe no rascunho do Córtex. A
 * separação é proposital — o motor de reconhecimento pode mudar sem que o
 * encaixe mude, e o encaixe pode ser testado sem motor nenhum.
 *
 * <p>Nada aqui vira RDO sozinho: o resultado é um rascunho para conferência,
 * com a lista do que não deu para ler. Papel molhado, letra apagada e coluna
 * ambígua são o caso comum, não a exceção.
 */

export interface PalavraReconhecida {
  texto: string;
  /** Canto esquerdo da palavra, na mesma escala de `largura`. */
  x: number;
  /** Topo da palavra, crescendo para baixo. */
  y: number;
  largura: number;
  altura: number;
}

export interface PaginaReconhecida {
  palavras: PalavraReconhecida[];
  larguraPagina: number;
  alturaPagina: number;
}

export interface LeituraDeRdoManuscrito {
  draft: RdoDraft;
  camposLidos: string[];
  pendencias: string[];
}

/*
 * Os cargos impressos na frente do formulário, na ordem das duas colunas. A
 * lista é o gabarito: cargo que não está aqui não existe no papel, e cargo
 * daqui que não aparecer na leitura simplesmente não foi preenchido.
 */
const CARGOS_DO_FORMULARIO = [
  "Engenheiro", "Encarregado", "Lider", "Tecnico Seg.",
  "Operador Vibro.", "Operador Rolo.", "Operador MiniCarregad.",
  "Mesista", "Apontador", "Motorista", "Ajudante", "Rasteleiro",
  "Laboratorista", "Operador Fresa", "Greidista", "Mecanico",
  "Operador Espargidor", "Tecnico Meio Ambiente",
  "Supervisor de Produção", "Encarregado Geral de Obra", "Vigia",
] as const;

const EQUIPAMENTOS_DO_FORMULARIO = [
  "Fresadora", "Ônibus", "Carreta Prancha", "Espargidor",
  "VibroAcabadoura", "Rolo Compactador Liso 01",
  "Rolo Compactador Liso 02", "Rolo Compactador Pneu 01",
  "Rolo Compactador Pneu 02", "Basculante 01", "Basculante 02",
  "Basculante 03", "Basculante 04", "C. Sin. Horizontal",
  "C. Sinali. Obra", "Compressor", "Caminhão Pipa", "Veículo Leve",
  "WC (Banheiro)", "Torre Iluminação", "Serra Clipper", "Rompedor",
  "Basculante (VIGA)",
] as const;

const PERIODOS = [
  { rotulo: "Manhã", campo: "condicaoManha" },
  { rotulo: "Tarde", campo: "condicaoTarde" },
  { rotulo: "Noite", campo: "condicaoNoite" },
] as const;

const TEMPO_POR_COLUNA: Record<string, CondicaoClimatica> = {
  BOM: "BOM",
  CHUVA: "CHUVA",
  IMPRODUTIVO: "IMPOSSIBILITADO",
};

export function normalizarRotulo(valor: string): string {
  return valor
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^0-9a-zA-Z ]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .toUpperCase();
}

interface Linha {
  y: number;
  palavras: PalavraReconhecida[];
}

/*
 * O reconhecimento devolve palavras soltas; a tabela só existe quando elas
 * voltam a ser linha. A tolerância é fração da altura da própria palavra, e
 * não um número fixo, porque a mesma folha digitalizada em resoluções
 * diferentes muda de escala inteira.
 */
export function agruparEmLinhas(palavras: readonly PalavraReconhecida[]): Linha[] {
  const ordenadas = [...palavras].sort((esquerda, direita) =>
    Math.abs(esquerda.y - direita.y) > Math.max(esquerda.altura, direita.altura) * 0.6
      ? esquerda.y - direita.y
      : esquerda.x - direita.x,
  );
  const linhas: Linha[] = [];
  for (const palavra of ordenadas) {
    const atual = linhas[linhas.length - 1];
    if (atual && Math.abs(atual.y - palavra.y) <= palavra.altura * 0.6) {
      atual.palavras.push(palavra);
      continue;
    }
    linhas.push({ y: palavra.y, palavras: [palavra] });
  }
  for (const linha of linhas) {
    linha.palavras.sort((esquerda, direita) => esquerda.x - direita.x);
  }
  return linhas;
}

function textoDaLinha(linha: Linha): string {
  return linha.palavras.map((palavra) => palavra.texto).join(" ");
}

/**
 * Onde começa cada coluna, lido do próprio cabeçalho impresso.
 *
 * <p>Ancorar nas palavras do cabeçalho, em vez de fatiar a página em partes
 * iguais, é o que faz a leitura sobreviver à folha torta e ao recorte
 * diferente: se "Terceiros" andou dois centímetros, a coluna andou junto.
 */
export function ancorasDeColuna(
  linhas: readonly Linha[],
  rotulos: readonly string[],
): Map<string, number[]> {
  const ancoras = new Map<string, number[]>();
  for (const rotulo of rotulos) {
    const alvo = normalizarRotulo(rotulo);
    const posicoes: number[] = [];
    for (const linha of linhas) {
      for (const palavra of linha.palavras) {
        if (normalizarRotulo(palavra.texto) === alvo) {
          posicoes.push(palavra.x + palavra.largura / 2);
        }
      }
    }
    if (posicoes.length > 0) {
      ancoras.set(alvo, posicoes.sort((esquerda, direita) => esquerda - direita));
    }
  }
  return ancoras;
}

function colunaMaisProxima(
  centro: number,
  ancoras: Map<string, number[]>,
): string | null {
  let escolhida: string | null = null;
  let menorDistancia = Number.POSITIVE_INFINITY;
  for (const [rotulo, posicoes] of ancoras) {
    for (const posicao of posicoes) {
      const distancia = Math.abs(posicao - centro);
      if (distancia < menorDistancia) {
        menorDistancia = distancia;
        escolhida = rotulo;
      }
    }
  }
  return escolhida;
}

function numeroEscrito(texto: string): number | null {
  const limpo = texto.replace(/[^0-9,.-]/g, "").replace(/\.(?=\d{3}\b)/g, "");
  if (!limpo || !/\d/.test(limpo)) return null;
  const valor = Number(limpo.replace(",", "."));
  return Number.isFinite(valor) ? valor : null;
}

function linhaDoRotulo(linhas: readonly Linha[], rotulo: string): Linha | null {
  const alvo = normalizarRotulo(rotulo);
  for (const linha of linhas) {
    for (const palavra of linha.palavras) {
      if (normalizarRotulo(palavra.texto) === alvo) return linha;
    }
    if (normalizarRotulo(textoDaLinha(linha)).includes(alvo)) return linha;
  }
  return null;
}

/*
 * Os rótulos impressos da folha. Servem de parede: o que a pessoa escreveu
 * ao lado de "Rodovia" termina onde começa "Data", e sem essa parede o valor
 * de um campo engolia o do vizinho na mesma linha da tabela.
 */
const ROTULOS_IMPRESSOS = [
  "Obra", "Nº da Obra", "Rodovia", "Data", "Dia da Semana", "Periodo",
  "Tempo", "Bom", "Chuva", "Improdutivo", "Pluviometria", "Km Inicial",
  "Km Final", "Hora Inicial", "Hora Final", "Diurno", "Noturno",
  "Trecho Interditado", "Condições Climaticas", "Interdição da Pista",
  "Turno de Trabalho", "Cargos", "Contratado", "Terceiros", "Equipamentos",
  "Próprio", "Locado", "Prefixo", "Material", "Quant", "Unid",
  "Nota Fiscal", "Total Usinado", "Total Aplicado", "Sobra", "Obs",
] as const;

const PRIMEIRAS_PALAVRAS_DE_ROTULO = new Set(
  ROTULOS_IMPRESSOS.map((rotulo) => normalizarRotulo(rotulo).split(" ")[0]),
);

/*
 * O que a pessoa escreveu ao lado do rótulo impresso, e só isso: consome o
 * rótulo inteiro — "Hora Inicial" são duas palavras, não uma — e para no
 * rótulo seguinte.
 */
function valorAoLadoDe(linhas: readonly Linha[], rotulo: string): string {
  const linha = linhaDoRotulo(linhas, rotulo);
  if (!linha) return "";
  const alvo = normalizarRotulo(rotulo);
  const palavras = linha.palavras;

  let indice = 0;
  while (
    indice < palavras.length &&
    !alvo.startsWith(normalizarRotulo(palavras[indice].texto))
  ) {
    indice += 1;
  }
  let lido = "";
  while (indice < palavras.length) {
    const candidato = `${lido} ${normalizarRotulo(palavras[indice].texto)}`.trim();
    if (!alvo.startsWith(candidato)) break;
    lido = candidato;
    indice += 1;
  }
  if (lido !== alvo) return "";

  const restante: string[] = [];
  for (const palavra of palavras.slice(indice)) {
    if (PRIMEIRAS_PALAVRAS_DE_ROTULO.has(normalizarRotulo(palavra.texto))) {
      break;
    }
    restante.push(palavra.texto);
  }
  return restante.join(" ").trim();
}

function dataIsoDe(texto: string): string {
  const encontrada = /(\d{1,2})\s*[/.-]\s*(\d{1,2})\s*[/.-]\s*(\d{2,4})/.exec(texto);
  if (!encontrada) return "";
  const [, dia, mes, ano] = encontrada;
  const anoCompleto = ano.length === 2 ? `20${ano}` : ano.padStart(4, "0");
  const mesNumero = Number(mes);
  const diaNumero = Number(dia);
  if (mesNumero < 1 || mesNumero > 12 || diaNumero < 1 || diaNumero > 31) {
    return "";
  }
  return `${anoCompleto}-${String(mesNumero).padStart(2, "0")}-${String(diaNumero).padStart(2, "0")}`;
}

function horaDe(texto: string): string {
  const encontrada = /(\d{1,2})\s*[:h]\s*(\d{2})/i.exec(texto);
  if (!encontrada) return "";
  const hora = Number(encontrada[1]);
  const minuto = Number(encontrada[2]);
  if (hora > 23 || minuto > 59) return "";
  return `${String(hora).padStart(2, "0")}:${String(minuto).padStart(2, "0")}`;
}

function marcado(texto: string): boolean {
  return /^[xX✓✔]{1,2}$/.test(texto.trim());
}

function lerCondicoesClimaticas(
  linhas: readonly Linha[],
  draft: RdoDraft,
  camposLidos: string[],
): void {
  const colunas = ancorasDeColuna(linhas, ["Bom", "Chuva", "Improdutivo"]);
  if (colunas.size === 0) return;
  for (const periodo of PERIODOS) {
    const linha = linhaDoRotulo(linhas, periodo.rotulo);
    if (!linha) continue;
    for (const palavra of linha.palavras) {
      if (!marcado(palavra.texto)) continue;
      const coluna = colunaMaisProxima(palavra.x + palavra.largura / 2, colunas);
      const condicao = coluna ? TEMPO_POR_COLUNA[coluna] : undefined;
      if (condicao) {
        draft[periodo.campo] = condicao;
        camposLidos.push(`Clima ${periodo.rotulo.toLowerCase()}`);
      }
    }
  }
}

function lerMaoObra(
  linhas: readonly Linha[],
  draft: RdoDraft,
  camposLidos: string[],
): void {
  const colunas = ancorasDeColuna(linhas, ["Contratado", "Terceiros"]);
  if (colunas.size === 0) return;
  for (const cargo of CARGOS_DO_FORMULARIO) {
    const linha = linhaDoRotulo(linhas, cargo);
    if (!linha) continue;
    const alvo = normalizarRotulo(cargo);
    for (const palavra of linha.palavras) {
      if (alvo.includes(normalizarRotulo(palavra.texto))) continue;
      const quantidade = numeroEscrito(palavra.texto);
      if (quantidade === null || quantidade <= 0) continue;
      const coluna = colunaMaisProxima(palavra.x + palavra.largura / 2, colunas);
      if (!coluna) continue;
      draft.maoObra.push({
        ...createEmptyMaoObra(),
        cargo,
        tipoVinculo: coluna === "TERCEIROS" ? "TERCEIRIZADO" : "PROPRIO",
        quantidade,
        selected: true,
        availability: "AVAILABLE",
      });
      camposLidos.push(`Mão de obra ${cargo}`);
    }
  }
}

function lerEquipamentos(
  linhas: readonly Linha[],
  draft: RdoDraft,
  camposLidos: string[],
): void {
  const colunas = ancorasDeColuna(linhas, ["Próprio", "Locado", "Prefixo"]);
  if (colunas.size === 0) return;
  for (const equipamento of EQUIPAMENTOS_DO_FORMULARIO) {
    const linha = linhaDoRotulo(linhas, equipamento);
    if (!linha) continue;
    const alvo = normalizarRotulo(equipamento);
    let quantidade: number | null = null;
    let vinculo = "";
    let prefixo = "";
    for (const palavra of linha.palavras) {
      if (alvo.includes(normalizarRotulo(palavra.texto))) continue;
      const coluna = colunaMaisProxima(palavra.x + palavra.largura / 2, colunas);
      if (coluna === "PREFIXO") {
        prefixo = `${prefixo} ${palavra.texto}`.trim();
        continue;
      }
      const valor = numeroEscrito(palavra.texto);
      if (valor === null || valor <= 0) continue;
      quantidade = (quantidade ?? 0) + valor;
      vinculo = coluna === "LOCADO" ? "LOCADO" : "PROPRIO";
    }
    if (quantidade === null && !prefixo) continue;
    draft.equipamentos.push({
      ...createEmptyEquipamento(),
      descricao: equipamento,
      prefixo,
      tipoVinculo: vinculo || "PROPRIO",
      quantidade: quantidade ?? 1,
    });
    camposLidos.push(`Equipamento ${equipamento}`);
  }
}

/*
 * Produção/Segmentos é a única tabela do papel de linhas livres: a pessoa
 * escreve quantos trechos couberem. Ela é reconhecida pela forma — km inicial
 * e km final no padrão de estaca — e não por rótulo, que só existe no
 * cabeçalho.
 */
const ESTACA = /^(\d{1,4})\+(\d{1,3})$/;

/*
 * O papel escreve o trecho em estaca — "206+822" é o km 206 mais 822 metros.
 * O Córtex guarda km decimal, que é o que a conta de extensão sabe ler:
 * `Number("206+822")` não é número, e o trecho inteiro aparecia sem
 * comprimento. Traduzir a notação não muda o valor, só a escrita.
 */
function kmDaEstaca(texto: string): string {
  const partes = ESTACA.exec(texto.trim());
  if (!partes) return texto.trim();
  return `${partes[1]},${partes[2].padStart(3, "0")}`;
}

function lerProducao(
  linhas: readonly Linha[],
  draft: RdoDraft,
  camposLidos: string[],
  pendencias: string[],
): void {
  let leuAlguma = false;
  for (const linha of linhas) {
    const palavras = linha.palavras;
    if (palavras.length < 3) continue;
    const inicial = palavras[0]?.texto.trim() ?? "";
    const final = palavras[1]?.texto.trim() ?? "";
    if (!ESTACA.test(inicial) || !ESTACA.test(final)) continue;

    const numeros: number[] = [];
    const textos: string[] = [];
    for (const palavra of palavras.slice(2)) {
      const valor = numeroEscrito(palavra.texto);
      if (valor !== null && /^[\d.,]+$/.test(palavra.texto.trim())) {
        numeros.push(valor);
        continue;
      }
      textos.push(palavra.texto);
    }
    // Comprimento, largura e espessura, nessa ordem impressa. O comprimento
    // não é copiado: o Córtex o calcula do próprio trecho, e guardá-lo ao
    // lado criaria duas versões da mesma medida.
    const largura = numeros[1] ?? null;
    const espessura = numeros[2] ?? null;
    const descricao = textos.join(" ").replace(/\s+/g, " ").trim();
    const [pista, atividade] = descricao.includes("/")
      ? descricao.split("/", 2).map((parte) => parte.trim())
      : ["", descricao];

    draft.servicosExecutados.push({
      ...createEmptyServicoExecutado(),
      trechoInicial: kmDaEstaca(inicial),
      trechoFinal: kmDaEstaca(final),
      pista,
      larguraM: largura ?? "",
      // A coluna de espessura do papel é escrita em metros (0,070); o Córtex
      // guarda em centímetros. Abaixo de um metro só pode ser metro — sete
      // centímetros de capa não viram sete metros.
      espessuraCm:
        espessura === null
          ? ""
          : espessura < 1
            ? Math.round(espessura * 100_000) / 1_000
            : espessura,
      servicoNome: atividade,
    });
    leuAlguma = true;
    camposLidos.push(`Produção ${inicial} a ${final}`);
  }
  if (leuAlguma) {
    pendencias.push(
      "A quantidade executada de cada trecho não existe no formulário de papel; preencha antes de exportar.",
    );
  }
}

function lerMateriais(
  linhas: readonly Linha[],
  draft: RdoDraft,
  camposLidos: string[],
  pendencias: string[],
): void {
  const ambiguos: string[] = [];
  for (const linha of linhas) {
    const palavras = linha.palavras;
    if (palavras.length < 2) continue;
    const nome = palavras[0]?.texto.trim() ?? "";
    if (!/^[A-Za-zÀ-ÿ][A-Za-zÀ-ÿ0-9.-]{2,}$/.test(nome)) continue;
    const quantidade = numeroEscrito(palavras[1]?.texto ?? "");
    if (quantidade === null) continue;
    const notaFiscal = palavras
      .slice(2)
      .map((palavra) => palavra.texto)
      .join(" ")
      .trim();
    if (!notaFiscal) continue;
    draft.materiais.push({
      ...createEmptyMaterial(),
      materialNome: nome,
      quantidadeUsinada: quantidade,
      notaFiscal,
    });
    camposLidos.push(`Material ${nome}`);
    /*
     * "24.040" é 24 mil ou 24 vírgula 040? A regra do país diz milhar, a
     * balança da usina diz tonelada com três casas, e o papel não diz qual
     * das duas a pessoa quis. Escolher em silêncio erraria a nota fiscal por
     * mil vezes, então o número entra pela regra e a dúvida vai para a lista.
     */
    if (/^\d+\.\d{3}$/.test(palavras[1]?.texto.trim() ?? "")) {
      ambiguos.push(nome);
    }
  }
  if (ambiguos.length > 0) {
    pendencias.push(
      `Confira a escala da quantidade de ${[...new Set(ambiguos)].join(", ")}: o ponto pode ser milhar ou decimal no papel.`,
    );
  }
}

/**
 * O papel virado rascunho.
 *
 * <p>O controle geométrico do formulário fica em branco na obra, e continua em
 * branco aqui: nenhuma linha é criada por ele. O encaixe é só com o que o RDO
 * do Córtex já tem.
 */
export function interpretarRdoManuscrito(
  paginas: readonly PaginaReconhecida[],
): LeituraDeRdoManuscrito {
  const draft = createEmptyRdo();
  const camposLidos: string[] = [];
  const pendencias: string[] = [];

  const frente = paginas[0] ? agruparEmLinhas(paginas[0].palavras) : [];
  const versoPagina = paginas[1] ?? paginas[0];
  const verso = versoPagina ? agruparEmLinhas(versoPagina.palavras) : [];

  const cabecalho = [...frente, ...verso];
  const data = dataIsoDe(
    valorAoLadoDe(cabecalho, "Data") || textoDaLinha({ y: 0, palavras: cabecalho.flatMap((linha) => linha.palavras) }),
  );
  if (data) {
    draft.dataRdo = data;
    camposLidos.push("Data");
  } else {
    pendencias.push("A data do RDO não foi reconhecida.");
  }

  const rodovia = valorAoLadoDe(frente, "Rodovia");
  if (rodovia) {
    draft.rodovia = rodovia;
    camposLidos.push("Rodovia");
  }
  const contrato = valorAoLadoDe(frente, "Nº da Obra");
  if (contrato) {
    draft.contrato = contrato;
    camposLidos.push("Nº da Obra");
  }

  lerCondicoesClimaticas(frente, draft, camposLidos);

  const pluviometria = numeroEscrito(valorAoLadoDe(frente, "Pluviometria"));
  if (pluviometria !== null) {
    draft.pluviometriaMm = pluviometria;
    camposLidos.push("Pluviometria");
  }

  const horaInicial = horaDe(valorAoLadoDe(frente, "Hora Inicial"));
  const horaFinal = horaDe(valorAoLadoDe(frente, "Hora Final"));
  if (horaInicial) {
    draft.horaInicio = horaInicial;
    camposLidos.push("Hora inicial");
  }
  if (horaFinal) {
    draft.horaFim = horaFinal;
    camposLidos.push("Hora final");
  }

  const kmInicial = valorAoLadoDe(frente, "Km Inicial");
  const kmFinal = valorAoLadoDe(frente, "Km Final");
  if (kmInicial) draft.kmInicialInterditado = kmInicial;
  if (kmFinal) draft.kmFinalInterditado = kmFinal;

  lerMaoObra(frente, draft, camposLidos);
  lerEquipamentos(frente, draft, camposLidos);
  lerProducao(frente, draft, camposLidos, pendencias);
  lerMateriais(verso, draft, camposLidos, pendencias);

  const observacao = valorAoLadoDe(verso, "Obs.");
  if (observacao) {
    draft.observacoes = observacao;
    camposLidos.push("Observações");
  }

  if (draft.maoObra.length === 0) {
    pendencias.push("Nenhuma linha de mão de obra foi reconhecida.");
  }
  if (draft.servicosExecutados.length === 0) {
    pendencias.push("Nenhum trecho de produção foi reconhecido.");
  }

  return { draft, camposLidos, pendencias };
}
