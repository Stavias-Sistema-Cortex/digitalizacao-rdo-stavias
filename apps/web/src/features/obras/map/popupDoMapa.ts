import { rotuloDaCategoria, rotuloDaFonte } from "./mapCategories";
import { ROTULO_POR_FASE, servicoDaFeature } from "./execucaoDoTrecho";

/**
 * O balão que o mapa Leaflet abre sobre uma geometria.
 *
 * <p>Fica fora do componente porque também é onde mora a lixeira do ponto
 * operacional, e essa decisão — quem pode sair do mapa e quem não pode —
 * merece ser lida e testada sozinha, sem subir um mapa inteiro para isso.
 */

export function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

/**
 * Lixeira desenhada aqui dentro.
 *
 * A política de conteúdo não busca ícone de fora, e o mapa precisa continuar
 * inteiro offline — que é quando marcações erradas costumam ser notadas.
 */
const LIXEIRA_SVG =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
  'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ' +
  'aria-hidden="true" focusable="false">' +
  '<path d="M4 7h16M10 4h4M6 7l1 13h10l1-13M10 11v6M14 11v6" /></svg>';

/** Lápis, pelo mesmo motivo da lixeira: nada é buscado de fora. */
const LAPIS_SVG =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
  'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ' +
  'aria-hidden="true" focusable="false">' +
  '<path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4Z" /></svg>';

/** Número de quilômetro como o Brasil escreve: vírgula, até três casas. */
const KM_LEGIVEL = new Intl.NumberFormat("pt-BR", {
  // Notação de estaca: km 196+120 se escreve 196,120. Sem o mínimo, o mesmo
  // balão mostrava "172,133 ao 196,12" — duas precisões na mesma régua.
  minimumFractionDigits: 3,
  maximumFractionDigits: 3,
});

/**
 * Uma ponta de quilômetro, venha ela como veio.
 *
 * <p>O eixo grava o quilômetro como número nas propriedades da linha; o trecho
 * derivado chega como texto já projetado. O balão lia só texto, e por isso o
 * eixo — justamente a régua de tudo — abria sem quilômetro nenhum.
 */
function pontaDeQuilometro(valor: unknown): string {
  if (typeof valor === "number" && Number.isFinite(valor)) {
    return KM_LEGIVEL.format(valor);
  }
  return typeof valor === "string" ? valor.trim() : "";
}

/**
 * "km 206,822 ao 207,100" — ou só um extremo, quando é só isso que se sabe.
 *
 * <p>O trecho pela metade continua valendo: um apontamento com o km inicial e
 * sem o final descreve por onde a frente começou, e esconder isso por não estar
 * completo apagaria a única referência que a linha tem.
 */
export function quilometroDoBalao(
  properties: Record<string, unknown>,
): string | null {
  return quilometragem(properties);
}

function quilometragem(properties: Record<string, unknown>): string | null {
  const inicial = pontaDeQuilometro(properties.kmInicial);
  const fim = pontaDeQuilometro(properties.kmFinal);
  if (inicial && fim) return `km ${inicial} ao ${fim}`;
  if (inicial) return `a partir do km ${inicial}`;
  if (fim) return `até o km ${fim}`;
  return null;
}

/** "2026-08-11..." como se lê: 11/08/2026. */
function dataLegivel(valor: string): string {
  const [ano, mes, dia] = valor.slice(0, 10).split("-");
  if (!ano || !mes || !dia) return valor.slice(0, 10);
  return `${dia}/${mes}/${ano}`;
}

/**
 * O nome pelo qual o balão abre — o mesmo nos dois painéis.
 *
 * <p>O painel vetorial tinha regra própria e ela mostrava o pior dos casos: o
 * eixo abria como "EIXO_OBRA", a grafia do banco, com a mesma sigla repetida
 * na linha de baixo. O eixo tem nome de gente porque é a única geometria com
 * papel próprio — é a régua da obra, não uma camada qualquer.
 */
export function tituloDoBalao(properties: Record<string, unknown>): string {
  if (typeof properties.nome === "string" && properties.nome.trim()) {
    return properties.nome.trim();
  }
  if (properties.categoria === "EIXO_OBRA") {
    return "Eixo da obra";
  }
  return rotuloDaCategoria(properties.categoria);
}

/**
 * A linha de detalhes do balão, sem a origem — ela fecha o balão à parte.
 *
 * <p>Compartilhada entre os dois painéis para acabarem as duas verdades: o
 * Leaflet montava uma frase, o vetorial montava outra, e a mesma linha clicada
 * dizia coisas diferentes conforme o lado da tela.
 */
export function detalhesDoBalao(
  properties: Record<string, unknown>,
): string[] {
  const titulo = tituloDoBalao(properties);
  const servico = servicoDaFeature({ properties } as never);
  const fase = properties.faseExecucao;
  const rotuloCategoria = rotuloDaCategoria(properties.categoria);
  return [
    // O quilômetro NÃO entra aqui: ele tem linha própria no balão, porque é a
    // primeira coisa que se pergunta olhando para uma linha numa rodovia — e
    // linha de destaque não se mistura com metadado.
    // A rodovia diz sobre qual estrada a régua fala — o eixo a carrega.
    typeof properties.rodovia === "string" && properties.rodovia.trim()
      ? properties.rodovia.trim()
      : null,
    // Cidade e pista vêm do próprio RDO na linha derivada: é a língua em que
    // a obra descreve onde o trabalho aconteceu.
    typeof properties.cidade === "string" && properties.cidade.trim()
      ? properties.cidade.trim()
      : null,
    typeof properties.pista === "string" && properties.pista.trim()
      ? `pista ${properties.pista.trim()}`
      : null,
    // O serviço vem antes de qualquer metadado: é o que o segmento REPRESENTA
    // no campo, e era a informação que existia no dado sem aparecer na tela.
    servico && servico !== titulo ? servico : null,
    typeof fase === "string" && fase in ROTULO_POR_FASE
      ? ROTULO_POR_FASE[fase as keyof typeof ROTULO_POR_FASE]
      : null,
    typeof properties.numeroRdo === "string"
      ? `RDO ${properties.numeroRdo}`
      : null,
    typeof properties.validoDesde === "string"
      ? `desde ${dataLegivel(properties.validoDesde)}`
      : null,
    // Repetir o título logo abaixo dele era o que fazia o balão parecer
    // gerado: "Eixo da obra · Eixo obra" não informa nada duas vezes. O eixo
    // fica de fora sempre, porque o título dele já é a categoria com outro
    // nome.
    typeof properties.categoria === "string" &&
    properties.categoria !== "EIXO_OBRA" &&
    rotuloCategoria !== titulo
      ? rotuloCategoria
      : null,
  ].filter((item): item is string => Boolean(item));
}

export function popupHtml(properties: Record<string, unknown>): string {
  const km = quilometroDoBalao(properties);
  const detalhes = [
    ...detalhesDoBalao(properties),
    typeof properties.fonte === "string"
      ? rotuloDaFonte(properties.fonte)
      : null,
  ].filter((item): item is string => Boolean(item));

  return `<strong>${escapeHtml(tituloDoBalao(properties))}</strong>${
    km ? `<span class="mapa-balao-km">${escapeHtml(km)}</span>` : ""
  }<span class="mapa-balao-detalhes">${escapeHtml(detalhes.join(" · "))}</span>`;
}

/**
 * O id da geometria de que este balão fala.
 *
 * <p>Vem das propriedades, e não do `id` da feature, porque o mapa vetorial
 * descarta identificador de texto e só entrega as propriedades no evento de
 * clique. Ler do mesmo lugar nos dois mapas é o que permite a lixeira existir
 * nos dois.
 */
export function geometriaDoBalao(
  properties: Record<string, unknown>,
): string | null {
  const id = properties.geometriaId;
  return typeof id === "string" && id ? id : null;
}

/**
 * O que pode sair do mapa por aqui.
 *
 * <p>O trecho ficou de fora por muito tempo, com uma justificativa que não se
 * sustenta mais: dizia-se que o desenho pertence ao RDO e sai junto com ele, e
 * que uma lixeira na linha abriria um segundo jeito de apagar o mesmo
 * trabalho. Mas o desenho não carrega trabalho nenhum — o quilômetro mora só
 * no apontamento, e a geometria guarda a forma, a rodovia, o sentido e a
 * faixa. Apagar a linha não apaga medida alguma; o RDO segue afirmando o mesmo
 * trecho.
 *
 * <p>Sem essa saída, quem desenhava torto e via depois não tinha o que fazer:
 * a linha errada ficava no mapa da obra para sempre, ou custava o apontamento
 * inteiro do dia.
 *
 * <p>O eixo também sai por aqui, e a decisão é do dono do sistema. Apagar a
 * régua não toca em RDO nenhum: os quilômetros continuam nos apontamentos, que
 * é onde moram — o que some são as linhas que o eixo derivava, porque sem
 * régua não há onde projetá-las. O botão "Cadastrar o eixo" volta à barra, e
 * uma régua nova reergue os mesmos trechos a partir dos mesmos números.
 *
 * <p>A localização da obra continua fora: ela não é geometria removível, é o
 * cadastro da obra.
 */
export function geometriaPodeSairDoMapa(
  properties: Record<string, unknown>,
): boolean {
  if (ehDerivadaDoEixo(properties)) {
    // A linha derivada tem lixeira própria: silêncio, não apagamento.
    return true;
  }
  return (
    (properties.categoria === "PONTO_OPERACIONAL" ||
      properties.categoria === "TRECHO" ||
      properties.categoria === "EIXO_OBRA") &&
    geometriaDoBalao(properties) !== null
  );
}

/**
 * A linha que o eixo derivou não é desenho, e a lixeira dela é outra.
 *
 * <p>Ela não existe como registro: nasce na leitura, do quilômetro que mora no
 * RDO. Removê-la não pode apagar o RDO — o mapa é projeção, o documento é o
 * fato. O que a lixeira guarda é um silêncio: a linha some do mapa, para todo
 * mundo, e o RDO segue intacto. O silêncio dura até o documento ser editado —
 * aí a hierarquia dele se reafirma e a linha volta na leitura seguinte.
 */
export function ehDerivadaDoEixo(
  properties: Record<string, unknown>,
): boolean {
  return properties.derivadoDoEixo === true;
}

/** O nome do que a lixeira remove, para o rótulo dizer a verdade. */
function rotuloDoRemovivel(properties: Record<string, unknown>): string {
  if (ehDerivadaDoEixo(properties)) return "linha do RDO no mapa";
  if (properties.categoria === "EIXO_OBRA") return "eixo da obra";
  return properties.categoria === "TRECHO"
    ? "trecho desenhado"
    : "ponto operacional";
}

/**
 * O que pode ter o traçado corrigido.
 *
 * <p>Apagar e desenhar de novo já resolvia a linha torta, mas conta outra
 * história: o histórico passa a ver um desenho morto e outro nascido, em vez
 * de uma correção — e o desenho novo é outro registro, que precisa ser
 * descrito de novo por inteiro. Corrigir mantém o mesmo trecho.
 *
 * <p>Só o trecho tem traçado a corrigir: o ponto operacional é uma coordenada
 * só, e remarcá-la é remarcar a posição, não redesenhar uma forma.
 *
 * <p>O que já saiu do mapa fica fora. Uma linha encerrada continua desenhada
 * como histórico, e o servidor recusa alterá-la — oferecer o lápis ali seria
 * oferecer um botão que só sabe falhar.
 */
export function trechoPodeSerRedesenhado(
  properties: Record<string, unknown>,
): boolean {
  if (properties.categoria !== "TRECHO" || properties.validoAte) {
    return false;
  }
  /*
   * A linha derivada corrige por outro caminho: ela não tem geometria a
   * reescrever, e sim um quilômetro. Arrastar os extremos vira quilômetro pela
   * régua do eixo e é escrito no apontamento do RDO — que é onde ele mora.
   * Para isso ela precisa dizer de qual linha de serviço fala.
   */
  if (ehDerivadaDoEixo(properties)) {
    return typeof properties.execucaoId === "string"
        && properties.execucaoId.trim() !== "";
  }
  return geometriaDoBalao(properties) !== null;
}

export function redesenhoDoBalao(
  properties: Record<string, unknown>,
  aoRedesenhar: ((id: string) => void) | null,
): HTMLButtonElement | null {
  if (!aoRedesenhar || !trechoPodeSerRedesenhado(properties)) {
    return null;
  }
  // Vale para as duas: a derivada não tem geometria gravada, mas a feição
  // carrega o mesmo campo, e nele vai o id que aponta para a linha de serviço.
  const id = geometriaDoBalao(properties);
  if (!id) {
    return null;
  }
  const rotulo = ehDerivadaDoEixo(properties)
    ? "Corrigir o quilômetro deste trecho no RDO"
    : "Corrigir o traçado deste trecho";
  const botao = document.createElement("button");
  botao.type = "button";
  botao.className = "mapa-balao-redesenhar";
  botao.title = rotulo;
  botao.setAttribute("aria-label", rotulo);
  botao.innerHTML = LAPIS_SVG;
  botao.addEventListener("click", (evento) => {
    evento.stopPropagation();
    aoRedesenhar(id);
  });
  return botao;
}

export function lixeiraDoBalao(
  properties: Record<string, unknown>,
  aoRemover: ((id: string) => void) | null,
): HTMLButtonElement | null {
  const id = geometriaDoBalao(properties);
  if (!id || !aoRemover || !geometriaPodeSairDoMapa(properties)) {
    return null;
  }
  const rotulo = `Remover ${rotuloDoRemovivel(properties)}`;
  const botao = document.createElement("button");
  botao.type = "button";
  botao.className = "mapa-balao-remover";
  botao.title = rotulo;
  botao.setAttribute("aria-label", rotulo);
  botao.innerHTML = LIXEIRA_SVG;
  botao.addEventListener("click", (evento) => {
    // O clique é do botão, não do mapa: sem isto o painel vetorial reabre o
    // balão por baixo da confirmação que acabou de ser pedida.
    evento.stopPropagation();
    aoRemover(id);
  });
  return botao;
}

/** Conteúdo do balão do painel Leaflet. */
export function popupElement(
  properties: Record<string, unknown>,
  aoRemover: ((id: string) => void) | null,
  aoRedesenhar: ((id: string) => void) | null = null,
): HTMLElement {
  const raiz = document.createElement("div");
  raiz.className = "mapa-balao";
  raiz.innerHTML = popupHtml(properties);
  // O lápis vem antes da lixeira: corrigir é o que quase sempre se quer ao
  // olhar para uma linha errada, e apagar é a saída de quem não quer o
  // desenho de jeito nenhum.
  const lapis = redesenhoDoBalao(properties, aoRedesenhar);
  if (lapis) raiz.appendChild(lapis);
  const lixeira = lixeiraDoBalao(properties, aoRemover);
  if (lixeira) raiz.appendChild(lixeira);
  return raiz;
}
