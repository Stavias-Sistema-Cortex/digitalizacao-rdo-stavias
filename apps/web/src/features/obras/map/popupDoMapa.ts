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
 * Só o ponto operacional sai do mapa por aqui.
 *
 * <p>Um trecho desenhado pertence ao RDO do dia e sai junto com ele; oferecer
 * a lixeira na linha do trecho abriria um segundo jeito de apagar o mesmo
 * trabalho, por fora do apontamento. A localização da obra não é geometria
 * removível: é o cadastro dela.
 */
export function pontoPodeSairDoMapa(
  properties: Record<string, unknown>,
): boolean {
  return (
    properties.categoria === "PONTO_OPERACIONAL" &&
    geometriaDoBalao(properties) !== null
  );
}

/**
 * A lixeira do balão, quando aquele ponto pode sair do mapa.
 *
 * <p>Elemento e não texto: o botão precisa de um ouvinte de verdade, e uma
 * string de HTML no balão não tem como carregar um. Devolve nulo quando não há
 * o que remover, para quem monta o balão não precisar repetir a regra.
 */
export function lixeiraDoBalao(
  properties: Record<string, unknown>,
  aoRemover: ((id: string) => void) | null,
): HTMLButtonElement | null {
  const id = geometriaDoBalao(properties);
  if (!id || !aoRemover || !pontoPodeSairDoMapa(properties)) {
    return null;
  }
  const botao = document.createElement("button");
  botao.type = "button";
  botao.className = "mapa-balao-remover";
  botao.title = "Remover ponto operacional";
  botao.setAttribute("aria-label", "Remover ponto operacional");
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
