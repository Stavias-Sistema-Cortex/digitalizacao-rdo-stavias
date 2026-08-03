/**
 * Política de falha do renderizador vetorial.
 *
 * Vive fora do adaptador porque é a decisão que apagava o painel: o MapLibre e
 * o Mapbox emitem `error` tanto para o que impede o mapa de existir quanto para
 * o que apenas o degrada, e tratar os dois do mesmo jeito custava a tela
 * inteira por causa de um ícone ausente no sprite.
 */

const MAX_MENSAGEM_ERRO = 160;

export interface FalhaDoRenderizador {
  message?: string;
  /** URL do recurso, quando o erro é de rede (`AJAXError`). */
  url?: string;
  /** Código HTTP, quando o servidor respondeu e recusou (`AJAXError`). */
  status?: number;
}

/**
 * Traduz a falha do renderizador para uma frase utilizável.
 *
 * O erro cru do WebGL traz um despejo de atributos de contexto que não diz nada
 * a quem está em campo; a causa conhecida vira instrução e o resto é truncado
 * em vez de vazar para a tela. Nenhuma URL sobrevive à tradução: o estilo do
 * provider pago carrega a chave na query, e ela não pode aparecer num aviso.
 */
export function mensagemDeFalha(bruta: string | undefined): string {
  const texto = bruta?.trim();
  if (!texto) {
    return "Falha ao carregar o mapa.";
  }
  if (/webgl/i.test(texto)) {
    return "Este navegador não conseguiu iniciar o WebGL, exigido pela visualização 3D. Use o painel Leaflet ao lado ou habilite a aceleração gráfica.";
  }
  if (/failed to fetch|networkerror|load failed/i.test(texto)) {
    return "Não foi possível baixar os tiles do mapa. Sem rede, apenas o que já foi visto fica disponível.";
  }
  const semUrl = texto
    .replace(/https?:\/\/\S+/g, "")
    .replace(/\s+/g, " ")
    .trim();
  if (!semUrl) {
    return "Falha ao carregar o mapa.";
  }
  return semUrl.length > MAX_MENSAGEM_ERRO
    ? `${semUrl.slice(0, MAX_MENSAGEM_ERRO)}…`
    : semUrl;
}

/**
 * Separa o erro que impede o mapa de existir do que apenas o degrada.
 *
 * O renderizador emite `error` para muita coisa recuperável: um ícone que falta
 * no sprite do estilo, um tile 404, uma faixa de glifos que não veio. Só o
 * estilo que não chega e o WebGL indisponível deixam o mapa sem como funcionar;
 * o resto vira aviso e o mapa continua de pé com o que conseguiu baixar.
 */
export function falhaImpedeOMapa(
  erro: FalhaDoRenderizador | undefined,
  styleUrl: string | null,
): boolean {
  const texto = erro?.message ?? "";
  if (/webgl/i.test(texto)) {
    return true;
  }
  if (!styleUrl) {
    return false;
  }
  return erro?.url === styleUrl || texto.includes(styleUrl);
}

/**
 * Reconhece o basemap que não veio: a fonte de tiles inalcançável.
 *
 * É o modo de falha mais traiçoeiro do renderizador. O estilo chega, o fundo
 * pinta, `load` dispara e `areTilesLoaded()` responde verdadeiro — porque uma
 * fonte que falhou não tem tile pendente. O painel fica um retângulo da cor do
 * fundo, sem erro visível, exatamente como observado em campo. O estilo em si
 * e o WebGL ficam de fora: esses têm caminho próprio, que derruba a montagem.
 */
export function falhaDeBasemap(
  erro: FalhaDoRenderizador | undefined,
  styleUrl: string | null,
): boolean {
  if (!erro || falhaImpedeOMapa(erro, styleUrl)) {
    return false;
  }
  // O servidor que responde e recusa some tão completamente quanto o que não
  // responde. Só a rede caída era reconhecida aqui, e o host de mapa aberto
  // que devolve 403 sob carga ou 404 numa faixa de tiles caía fora da conta —
  // o painel ficava vazio sem que nada nesta função acusasse.
  if (typeof erro.status === "number" && erro.status >= 400) {
    return true;
  }
  const texto = `${erro.message ?? ""} ${erro.url ?? ""}`;
  return /failed to fetch|networkerror|load failed|ajaxerror/i.test(texto);
}
