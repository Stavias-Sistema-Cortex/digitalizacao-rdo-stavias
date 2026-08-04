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
 * Conteúdo do balão, com a lixeira quando o ponto pode sair do mapa.
 *
 * <p>Elemento e não texto: o botão precisa de um ouvinte de verdade, e uma
 * string de HTML no balão do Leaflet não tem como carregar um.
 *
 * <p>Só o ponto operacional ganha lixeira. Um trecho desenhado pertence ao RDO
 * do dia e sai junto com ele; oferecer a lixeira na linha do trecho abriria um
 * segundo jeito de apagar o mesmo trabalho, por fora do apontamento.
 */
export function popupElement(
  properties: Record<string, unknown>,
  id: string | null,
  aoRemover: ((id: string) => void) | null,
): HTMLElement {
  const raiz = document.createElement("div");
  raiz.className = "leaflet-trecho-popup";
  raiz.innerHTML = popupHtml(properties);
  if (!id || !aoRemover || properties.categoria !== "PONTO_OPERACIONAL") {
    return raiz;
  }
  const botao = document.createElement("button");
  botao.type = "button";
  botao.className = "leaflet-trecho-remover";
  botao.title = "Remover ponto operacional";
  botao.setAttribute("aria-label", "Remover ponto operacional");
  botao.innerHTML = LIXEIRA_SVG;
  botao.addEventListener("click", () => aoRemover(id));
  raiz.appendChild(botao);
  return raiz;
}
