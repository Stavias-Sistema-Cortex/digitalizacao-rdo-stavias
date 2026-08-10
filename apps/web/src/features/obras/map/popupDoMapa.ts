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

export function popupHtml(properties: Record<string, unknown>): string {
  const titulo =
    typeof properties.nome === "string" && properties.nome
      ? properties.nome
      : rotuloDaCategoria(properties.categoria);
  const servico = servicoDaFeature({ properties } as never);
  const fase = properties.faseExecucao;
  const detalhes = [
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
      ? `desde ${properties.validoDesde.slice(0, 10)}`
      : null,
    typeof properties.categoria === "string"
      ? rotuloDaCategoria(properties.categoria)
      : null,
    typeof properties.fonte === "string"
      ? rotuloDaFonte(properties.fonte)
      : null,
  ].filter((item): item is string => Boolean(item));

  return `<strong>${escapeHtml(titulo)}</strong><span>${escapeHtml(
    detalhes.join(" · "),
  )}</span>`;
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
 * <p>A localização da obra continua fora: ela não é geometria removível, é o
 * cadastro da obra.
 */
export function geometriaPodeSairDoMapa(
  properties: Record<string, unknown>,
): boolean {
  return (
    (properties.categoria === "PONTO_OPERACIONAL" ||
      properties.categoria === "TRECHO") &&
    geometriaDoBalao(properties) !== null
  );
}

/** O nome do que a lixeira remove, para o rótulo dizer a verdade. */
function rotuloDoRemovivel(properties: Record<string, unknown>): string {
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
  return (
    properties.categoria === "TRECHO" &&
    geometriaDoBalao(properties) !== null &&
    !properties.validoAte
  );
}

export function redesenhoDoBalao(
  properties: Record<string, unknown>,
  aoRedesenhar: ((id: string) => void) | null,
): HTMLButtonElement | null {
  const id = geometriaDoBalao(properties);
  if (!id || !aoRedesenhar || !trechoPodeSerRedesenhado(properties)) {
    return null;
  }
  const rotulo = "Corrigir o traçado deste trecho";
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
