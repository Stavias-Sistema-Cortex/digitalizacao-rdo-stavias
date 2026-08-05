import type { OutboxMutationRecord } from "../db/db.types";

export const MARCA_DE_SUPERACAO =
  /^(?:NON_APPLIED_)?SUPERSEDED_BY:/i;

/**
 * A mutação já cedeu lugar a outra, e contá-la prende a tela num estado vencido.
 *
 * Reconciliar um conflito cria uma substituta e a envia; a original fica onde
 * está. Para OBRA ela é marcada como superada, para as demais entidades — RDO
 * inclusive — permanece em `CONFLICT` sem marca nenhuma. Os contadores liam o
 * status cru, então uma reconciliação bem-sucedida deixava o indicador vermelho
 * para sempre. E como `determineSyncUiStatus` consulta conflito antes de tudo,
 * esse resto vencido escondia pendência, revisão e erro por baixo dele.
 *
 * Nada é ocultado: quem responde pelo trabalho agora é a substituta, contada em
 * `pendingCount` até subir. Só por isso a exigência de existir substituta é
 * inegociável — sem ela, a original continua sendo a única cópia e continua
 * contando. Esconder conflito sem quem o substitua faria a tela dizer que subiu
 * o que não subiu, que é o erro caro deste app.
 *
 * <p>Esta regra vive em módulo próprio porque tem dois donos legítimos: quem
 * conta pendências para a tarja e quem descarta mutações mortas em lote. Uma
 * cópia em cada lado envelheceria diferente, e as duas discordariam sobre a
 * mesma fila — que é a classe de defeito que este arquivo existe para evitar.
 * O descarte é o consumidor mais perigoso: apagar uma original que ainda tem
 * substituta viva é apagar o ancestral de trabalho que vai subir.
 */
export function foiSubstituida(
  mutation: OutboxMutationRecord,
  todas: readonly OutboxMutationRecord[],
): boolean {
  const marca = mutation.blockedReason?.trim();
  const apelido = marca && MARCA_DE_SUPERACAO.test(marca)
    ? marca.slice(marca.indexOf(":") + 1).trim()
    : "";
  if (apelido) {
    /*
     * Ausente conta como substituída, e isso é deliberado. A substituta que
     * aplicou some da fila — pela poda, ou porque o ciclo a resolveu — e a
     * marca continua apontando para ela. Exigir presença faria toda original
     * cuja substituta deu certo ressuscitar como pendência de revisão: o
     * inverso do que aconteceu.
     *
     * O que se recusa é o caso oposto e verificável: a substituta está ali e
     * está morta. Era assim que 246 recusas se sustentavam aos pares, cada uma
     * segurando a outra, sem saída nem pelo descarte nem pelo reenvio.
     */
    const nomeada = todas.find(
      (candidata) => candidata.clientMutationId === apelido,
    );
    return !nomeada || estaViva(nomeada);
  }
  // Só o envelope canônico carrega causalidade; o legado não tem o campo, e
  // por isso nunca substitui ninguém — o que é o lado seguro do engano.
  return todas.some(
    (candidata) =>
      "causationId" in candidata &&
      candidata.causationId === mutation.clientMutationId &&
      estaViva(candidata),
  );
}

/**
 * A candidata ainda responde pelo trabalho, em vez de já ter morrido também.
 *
 * <p>Esta é a condição que faltava, e a falta tinha consequência concreta: numa
 * fila real, 246 recusas sobreviveram ao descarte em lote porque cada uma
 * apontava para outra igualmente recusada. A regra perguntava apenas se
 * <em>existia</em> substituta, então duas mortas se sustentavam mutuamente e
 * nenhuma das duas tinha saída — o descarte pulava as duas, o reenvio devolvia
 * a mesma recusa, e a tela ficava com o número travado.
 *
 * <p>A marca `SUPERSEDED_BY:` tinha o mesmo furo por outro caminho: era aceita
 * pelo formato, sem conferir se a substituta que ela nomeia sequer existe.
 * Reconciliação interrompida no meio deixava a marca escrita e a substituta
 * não, e a original virava intocável apontando para o nada.
 *
 * <p>Continua valendo o motivo original de existir a regra: enquanto houver
 * substituta viva, apagar a original é apagar o ancestral do trabalho que vai
 * subir. O que mudou é que "viva" passou a significar viva.
 */
function estaViva(mutation: OutboxMutationRecord): boolean {
  return mutation.status !== "REJECTED" && mutation.status !== "CONFLICT";
}

/**
 * Recusas que o reenvio não tem como reverter, por mais vezes que se peça.
 *
 * <p>`requeueMutationsInReview` troca a identidade da mutação e preserva
 * `baseVersao`. Isso resolve a recusa por regra de negócio que mudou: a
 * entrega é nova, o servidor volta a julgar, e o veredito arquivado da
 * identidade antiga deixa de valer. Não resolve nada quando a recusa é
 * <em>sobre</em> a versão-base, porque a versão-base é justamente o que ele
 * mantém — o servidor recusa de novo, idêntico.
 *
 * <p>Também não resolve falha local: se o aparelho não conseguiu aplicar ou
 * montar o envelope, reapresentá-lo repete a mesma falha, que nunca chegou a
 * ser opinião do servidor.
 */
const RECUSA_QUE_O_REENVIO_NAO_MUDA = new Set([
  "VERSION_CONFLICT",
  "LOCAL_RESULT_APPLY_INVALID",
  "LOCAL_CANONICAL_INVALID",
  "LOCAL_CANONICAL_UPLOAD_INVALID",
]);

/**
 * O reenvio tem chance real de mudar o desfecho desta mutação.
 *
 * <p>Existe porque a tela oferecia "Reenviar" como ação de destaque para uma
 * pilha inteira de conflitos de versão, e cada clique reproduzia o laço:
 * 345 reenviados, 345 recusados, mesmo número de volta. Um botão que não pode
 * funcionar, acima do que funciona, é pior que botão nenhum — ele consome a
 * tentativa de quem está tentando resolver.
 */
export function vaiAdiantarReenviar(
  mutation: OutboxMutationRecord,
  todas: readonly OutboxMutationRecord[],
): boolean {
  if (mutation.status !== "REJECTED") return false;
  if (foiSubstituida(mutation, todas)) return false;
  const codigo = mutation.lastSafeCode?.trim();
  return !codigo || !RECUSA_QUE_O_REENVIO_NAO_MUDA.has(codigo);
}
