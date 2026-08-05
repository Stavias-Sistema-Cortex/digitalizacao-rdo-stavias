/**
 * O que ainda falta preencher para salvar a participação.
 *
 * <p>A mensagem antiga nomeava os três campos sempre, tivesse faltado um ou
 * três. Quem já havia escolhido a pessoa e escrito o motivo lia uma acusação
 * sobre o que estava ali na tela, na frente dele, e concluía — com razão — que
 * o erro não fazia sentido. Um aviso que não distingue o que falta do que está
 * pronto ensina a ignorar avisos.
 */
export function camposQueFaltam(formulario: {
  colaboradorId: string;
  funcaoOperacionalId: string;
  motivo: string;
}): string[] {
  const faltando: string[] = [];
  if (!formulario.colaboradorId) faltando.push("a pessoa");
  if (!formulario.funcaoOperacionalId) faltando.push("a função operacional");
  if (!formulario.motivo.trim()) faltando.push("o motivo da alteração");
  return faltando;
}

export function mensagemDoQueFalta(faltando: string[]): string {
  if (faltando.length === 0) {
    return "Não foi possível salvar a participação.";
  }
  if (faltando.length === 1) {
    return `Informe ${faltando[0]}.`;
  }
  const ultimo = faltando[faltando.length - 1];
  return `Informe ${faltando.slice(0, -1).join(", ")} e ${ultimo}.`;
}
