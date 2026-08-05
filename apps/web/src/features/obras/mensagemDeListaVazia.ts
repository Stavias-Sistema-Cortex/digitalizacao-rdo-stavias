import type { AuthProfile } from "../auth/authSession";

/**
 * O que dizer quando a lista de obras aparece vazia.
 *
 * <p>Vazio tem três causas diferentes e a tela mostrava a mesma frase para as
 * três. A que mais confunde é a nova: quem é Beta só enxerga as obras em que
 * tem vínculo, então entrar no Córtex e não achar obra nenhuma é o desfecho
 * correto de um cadastro incompleto — e "Nenhuma obra encontrada" faz isso
 * parecer defeito do sistema, mandando a pessoa procurar suporte em vez de
 * pedir o vínculo.
 *
 * <p>A distinção é possível sem perguntar nada ao servidor: se havia obras
 * carregadas e a lista filtrada esvaziou, foi a busca; se não havia nenhuma e a
 * sessão não é de escopo global, foi o vínculo.
 */
export function mensagemDeListaVazia(
  totalCarregado: number,
  session: AuthProfile | null,
): string {
  if (totalCarregado > 0) {
    return "Nenhuma obra corresponde à busca.";
  }

  if (session && !session.escopoGlobal) {
    return (
      "Você ainda não está vinculado a nenhuma obra. " +
      "Peça a um administrador para vincular você em Gestão de Obras."
    );
  }

  return "Nenhuma obra encontrada.";
}
