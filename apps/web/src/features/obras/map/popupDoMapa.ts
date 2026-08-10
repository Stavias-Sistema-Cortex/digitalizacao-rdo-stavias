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
): HTMLElement {
  const raiz = document.createElement("div");
  raiz.className = "mapa-balao";
  raiz.innerHTML = popupHtml(properties);
  const lixeira = lixeiraDoBalao(properties, aoRemover);
  if (lixeira) raiz.appendChild(lixeira);
  return raiz;
}
