import { createEmptyMaoObra } from "./createEmptyRdo";
import type { MaoObraDraft } from "./rdo.types";
import type {
  RdoContextCollaborator,
  RdoPreviousWorkforceItem,
} from "./rdoLookupApi";

type IdFactory = () => string;

function compactTime(value: string | null): string {
  return value?.match(/^\d{2}:\d{2}/)?.[0] ?? "";
}

function collaboratorMap(
  catalog: readonly RdoContextCollaborator[],
): Map<string, RdoContextCollaborator> {
  return new Map(
    catalog
      .filter((item) => item.id.trim())
      .map((item) => [item.id, item]),
  );
}

export function carryForwardWorkforce(
  previousWorkers: readonly RdoPreviousWorkforceItem[],
  currentCatalog: readonly RdoContextCollaborator[],
  createId: IdFactory = () => crypto.randomUUID(),
): MaoObraDraft[] {
  const authorized = collaboratorMap(currentCatalog);
  const seen = new Set<string>();
  const rows: MaoObraDraft[] = [];

  for (const item of previousWorkers) {
    const collaboratorId = item.collaboratorId?.trim() ?? "";
    const identityKey = collaboratorId
      ? `collaborator:${collaboratorId}`
      : `evidence:${item.sourceRdoId}:${item.sourceItemId}`;
    if (seen.has(identityKey)) continue;
    seen.add(identityKey);

    const current = collaboratorId
      ? authorized.get(collaboratorId)
      : undefined;
    const availability = item.availability?.toUpperCase();
    const available = collaboratorId
      ? Boolean(current) && availability !== "UNAVAILABLE"
      : Boolean(item.nameSnapshot?.trim()) && availability !== "UNAVAILABLE";

    /*
     * Quem perdeu a autorização não atravessa para o RDO de hoje.
     *
     * A equipe é uma das duas portas de autorização da obra, e apagá-la fecha
     * essa porta — o servidor já devolve essas pessoas como indisponíveis. A
     * herança, porém, criava a linha assim mesmo, desmarcada e cinza, e ela
     * reaparecia todo dia: a equipe tinha sido apagada e os membros seguiam
     * escritos no RDO seguinte, e no seguinte, sem nenhum jeito de tirá-los a
     * não ser um a um.
     *
     * O RDO de ontem continua guardando quem trabalhou ontem — nada do
     * histórico é tocado. O que para é a repetição para a frente. Quem voltar
     * a ser autorizado entra pelo "Adicionar colaborador", que é a porta de
     * quem escolhe, em vez de aparecer sozinho.
     *
     * Nome digitado à mão é outra coisa e continua vindo: ele nunca dependeu
     * de equipe nenhuma, e é a única evidência de que aquela pessoa esteve na
     * frente de serviço.
     */
    if (collaboratorId && !available) {
      continue;
    }

    rows.push({
      ...createEmptyMaoObra(),
      localId: createId(),
      origemItemId: item.sourceItemId,
      sourceRdoId: item.sourceRdoId,
      origin: "PREVIOUS_RDO",
      availability: available ? "AVAILABLE" : "UNAVAILABLE",
      selected: available,
      colaboradorId: collaboratorId,
      /*
       * Quem ainda está no catálogo entra com o nome de hoje; o retrato do RDO
       * anterior só vale para quem saiu.
       *
       * Era o contrário, e o efeito se acumulava: cada RDO herda do anterior,
       * então um nome corrigido no Academy — ou grafado errado uma vez —
       * atravessava a cadeia inteira sem nunca alcançar a frente de serviço. Já
       * para quem não está mais na obra o retrato é a única evidência de quem
       * trabalhou naquele dia, e apagá-lo abriria um buraco no histórico.
       */
      nomeColaborador:
        current?.nome?.trim() || item.nameSnapshot?.trim() || "",
      /*
       * A função continua vindo do retrato. Diferente do nome, ela é o que foi
       * apontado naquele dia — o apontador pode tê-la ajustado de propósito, e
       * sobrescrever isso com o perfil do cadastro desfaria a escolha dele.
       */
      cargo:
        item.roleSnapshot?.trim() ||
        // O ofício do Academy vem antes do perfil de acesso: "PEDREIRO" é
        // cargo; "Apontador" é permissão de sistema.
        current?.funcao?.trim() ||
        current?.nomePerfil?.trim() ||
        "",
      tipoVinculo: item.linkType?.trim() ?? "",
      quantidade: item.quantity ?? "",
      horaInicio: compactTime(item.startTime),
      horaFim: compactTime(item.endTime),
      observacoes: item.observations?.trim() ?? "",
    });
  }

  return rows;
}

export function addAuthorizedWorkforceMember(
  rows: readonly MaoObraDraft[],
  collaboratorId: string,
  currentCatalog: readonly RdoContextCollaborator[],
  createId: IdFactory = () => crypto.randomUUID(),
): MaoObraDraft[] {
  const collaborator = collaboratorMap(currentCatalog).get(collaboratorId);
  if (!collaborator) {
    throw new Error("Colaborador não autorizado para esta obra.");
  }
  if (rows.some((row) => row.colaboradorId === collaboratorId)) {
    throw new Error("Este colaborador já está na equipe do RDO.");
  }

  return [
    ...rows,
    {
      ...createEmptyMaoObra(),
      localId: createId(),
      origin: "AUTHORIZED_CONTEXT",
      availability: "AVAILABLE",
      selected: true,
      colaboradorId: collaboratorId,
      nomeColaborador:
        collaborator.nome?.trim() ||
        collaborator.codigoColaborador?.trim() ||
        "",
      cargo:
        collaborator.funcao?.trim() ||
        collaborator.nomePerfil?.trim() ||
        collaborator.papelNaObra?.trim() ||
        "",
    },
  ];
}
