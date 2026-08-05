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
  if (marca && MARCA_DE_SUPERACAO.test(marca)) {
    return true;
  }
  // Só o envelope canônico carrega causalidade; o legado não tem o campo, e
  // por isso nunca substitui ninguém — o que é o lado seguro do engano.
  return todas.some(
    (candidata) =>
      "causationId" in candidata &&
      candidata.causationId === mutation.clientMutationId,
  );
}
