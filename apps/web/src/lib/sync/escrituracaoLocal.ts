/**
 * Escrituração da sincronização sobre o registro de domínio.
 *
 * Todo registro local carrega, além do dado do usuário, três campos que a
 * própria sincronização escreve para relatar o andamento dela: em que estado a
 * fila está, qual foi a última recusa e quando esse aviso chegou. Eles não são
 * dado de ninguém — são o recado do motor de sincronia para a interface.
 *
 * A distinção importa porque a pré-condição otimista das mutações locais
 * compara o registro que a tela leu com o que está no banco. Enquanto a fila
 * tem uma mutação recusada, cada rodada de sincronização reescreve esses três
 * campos; comparar o registro inteiro fazia toda ação do usuário perder a
 * corrida e falhar com "a entidade local mudou", de forma permanente. Quem
 * tentasse restaurar a obra para desatolar a fila era barrado justamente pelo
 * aviso de que a fila estava atolada.
 */

const CAMPOS_DE_ESCRITURACAO: ReadonlySet<string> = new Set([
  "syncStatus",
  "ultimoErro",
  "updatedAt",
]);

/**
 * O registro sem a escrituração da sincronização.
 *
 * Devolve o valor intacto quando não é um objeto simples — nulo, ausente ou
 * lista continuam comparáveis como estão.
 */
export function semEscrituracaoLocal(valor: unknown): unknown {
  if (valor === null || typeof valor !== "object" || Array.isArray(valor)) {
    return valor;
  }
  const domínio: Record<string, unknown> = {};
  for (const [chave, item] of Object.entries(
    valor as Record<string, unknown>,
  )) {
    if (CAMPOS_DE_ESCRITURACAO.has(chave)) {
      continue;
    }
    domínio[chave] = item;
  }
  return domínio;
}
