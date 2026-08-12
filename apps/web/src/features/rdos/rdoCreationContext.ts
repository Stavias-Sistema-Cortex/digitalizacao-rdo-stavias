import type { MaoObraDraft, RdoDraft } from "./rdo.types";
import type {
  RdoContextCollaborator,
  RdoCreationContextLookup,
  RdoLocalPendingCreationContextLookup,
} from "./rdoLookupApi";
import {
  addAuthorizedWorkforceMember,
  carryForwardWorkforce,
} from "./rdoWorkforceCarryForward";

export const RDO_CONTEXT_OFFLINE_MISSING =
  "Contexto desta obra ainda não está disponível offline.";
export const RDO_WORKFORCE_CATALOG_OFFLINE_UNAVAILABLE =
  "Colaboradores autorizados desta obra não estão disponíveis offline.";

export type RdoContextTruthStatus =
  | "FRESH"
  | "STALE"
  | "PARTIAL"
  | "LOCAL_PENDING";

/**
 * Qual RDO o contexto declara como anterior — a mesma resposta para todos.
 *
 * <p>Esta regra existia duas vezes, escrita de dois jeitos: aqui, montando o
 * rascunho, e em `hasCanonicalCreationIdentity`, validando a gravação. Uma
 * aceitava anterior de data igual, a outra exigia data estritamente menor.
 * Bastava existir um RDO do mesmo dia na mesma obra para o rascunho nascer
 * apontando para ele e a gravação exigir que não apontasse para nada: recusa
 * garantida, e nenhum segundo RDO do dia podia ser criado.
 *
 * <p>Quem decide quem é o anterior é o servidor, em `provenance.previousRdoId`
 * — as duas pontas já exigiam concordância com ele. O que faltava era
 * concordarem entre si, e por isso agora é uma função só.
 */
export function validPreviousRdo(
  context: RdoCreationContextLookup,
  currentRdoId = "",
): RdoCreationContextLookup["previousRdo"] {
  const previous = context.previousRdo;
  if (
    !previous ||
    previous.id === currentRdoId ||
    previous.dataRdo > context.data
  ) return null;
  if (context.provenance.previousRdoId !== previous.id) return null;
  return previous;
}

function complete(section: { status: string; complete: boolean; total: number; returned: number }) {
  return section.status === "COMPLETE" &&
    section.complete &&
    section.total >= 0 &&
    section.returned === section.total;
}

function explicitOptional(section: { status: string; complete: boolean; total: number; returned: number }) {
  return complete(section) ||
    (section.status === "NOT_CONFIGURED" &&
      section.complete === false &&
      section.total === 0 &&
      section.returned === 0);
}

export function isRdoCreationContextComplete(
  context: RdoCreationContextLookup,
): boolean {
  const { coverage } = context;
  return complete(coverage.previousWorkforce) &&
    complete(coverage.programacoes) &&
    complete(coverage.colaboradores) &&
    complete(coverage.equipamentos) &&
    explicitOptional(coverage.serviceCatalog) &&
    explicitOptional(coverage.priceCatalog);
}

/**
 * O quilômetro do eixo escrito como o campo do RDO espera lê-lo.
 *
 * <p>O servidor manda número; o campo é texto livre, e quem digita ali escreve
 * "206,822". Devolver "206.822" faria a leitura em pt-BR entender oitocentos
 * mil — o separador decimal daqui é a vírgula.
 *
 * <p>Sem eixo cadastrado, ou com eixo sem quilômetro, devolve vazio: sugerir
 * zero seria pior do que não sugerir nada.
 */
function kmDoEixo(valor: number | string | null | undefined): string {
  if (valor === null || valor === undefined) return "";
  const numero = typeof valor === "number" ? valor : Number(String(valor).trim());
  if (!Number.isFinite(numero)) return "";
  return new Intl.NumberFormat("pt-BR", {
    minimumFractionDigits: 3,
    maximumFractionDigits: 3,
  }).format(numero);
}

export function applyRdoCreationContext(
  draft: RdoDraft,
  context: RdoCreationContextLookup,
  createId: () => string = () => crypto.randomUUID(),
): RdoDraft {
  if (
    context.obra.id !== context.provenance.worksiteId ||
    context.data !== context.provenance.selectedDate
  ) {
    throw new Error("Proveniência do contexto de criação inválida.");
  }
  const previous = validPreviousRdo(context, draft.id);
  const previousWorkforce = previous
    ? context.previousWorkforce.filter(
        (item) => item.sourceRdoId === previous.id,
      )
    : [];

  return {
    ...draft,
    obraId: context.obra.id,
    dataRdo: context.data,
    numeroRdo: context.nextNumberSuggestion?.trim() ?? "",
    previousRdoId: previous?.id ?? "",
    previousRdoNumber: previous?.numeroRdo?.trim() ?? "",
    creationContextVersion: context.provenance.receiptVersion,
    programacaoId: "",
    cliente: context.obra.cliente?.trim() ?? "",
    contrato: context.obra.codigoContrato?.trim() ?? "",
    rodovia: context.obra.rodovia?.trim() ?? "",
    cidade: context.obra.cidade?.trim() ?? "",
    uf: context.obra.uf?.trim() ?? "",
    /*
     * O quilômetro do eixo chega preenchido, e continua editável.
     *
     * <p>O eixo é cadastrado uma vez e é a régua sobre a qual todo apontamento
     * vira trecho no mapa. Quem abria um RDO tinha de redigitar esse número de
     * cabeça, e o que ficava em branco ficava em branco para sempre — o campo
     * não se preenche sozinho depois.
     *
     * <p>Um rascunho que já traz quilômetro manda: ele está afirmando o trecho,
     * e essa afirmação vale mais do que a sugestão da obra. Vale para a
     * clonagem, que copia o trecho do RDO escolhido, e para o rascunho
     * retomado, que não pode ter o que foi digitado sobrescrito pela régua.
     */
    kmInicialInterditado: draft.kmInicialInterditado
      || kmDoEixo(context.obra.kmInicialEixo),
    kmFinalInterditado: draft.kmFinalInterditado
      || kmDoEixo(context.obra.kmFinalEixo),
    /*
     * A equipe que o rascunho já traz manda.
     *
     * <p>Aqui a mão de obra era sempre reconstruída do RDO anterior, e isso
     * atropelava a clonagem: quem clonava o RDO de segunda para repetir a
     * frente recebia a equipe de sexta, porque "anterior" é o RDO que antecede
     * a data nova, não o que foi escolhido para copiar. Um rascunho que já
     * chega com gente dentro está afirmando quem trabalhou, e essa afirmação
     * vale mais do que a herança automática.
     *
     * <p>Rascunho vazio — o caminho normal, de quem cria um RDO do zero —
     * continua herdando do anterior, que é o que poupa a digitação diária.
     */
    maoObra: draft.maoObra.length > 0
      ? draft.maoObra
      : carryForwardWorkforce(
          previousWorkforce,
          context.colaboradores,
          createId,
        ),
    apontadorColaboradorId: "",
    apontadorRdo: "",
    syncStatus: "LOCAL_ONLY",
  };
}

export function applyLocalPendingRdoCreationContext(
  draft: RdoDraft,
  context: RdoLocalPendingCreationContextLookup,
  createId: () => string = () => crypto.randomUUID(),
): RdoDraft {
  const previous = context.previousRdo &&
      context.previousRdo.id !== draft.id &&
      context.previousRdo.dataRdo <= context.data
    ? context.previousRdo
    : null;
  const previousWorkforce = previous
    ? context.previousWorkforce.filter(
        (item) => item.sourceRdoId === previous.id,
      )
    : [];

  return {
    ...draft,
    obraId: context.obra.id,
    dataRdo: context.data,
    previousRdoId: previous?.id ?? "",
    previousRdoNumber: previous?.numeroRdo?.trim() ?? "",
    creationContextVersion: null,
    programacaoId: "",
    cliente: context.obra.cliente?.trim() ?? "",
    contrato: context.obra.codigoContrato?.trim() ?? "",
    rodovia: context.obra.rodovia?.trim() ?? "",
    cidade: context.obra.cidade?.trim() ?? "",
    uf: context.obra.uf?.trim() ?? "",
    // Mesma regra do caminho com recibo: a equipe que o rascunho traz manda,
    // e o clone é justamente quem a traz.
    maoObra: draft.maoObra.length > 0
      ? draft.maoObra
      : carryForwardWorkforce(
          previousWorkforce,
          context.colaboradores,
          createId,
        ),
    apontadorColaboradorId: "",
    apontadorRdo: "",
    syncStatus: "LOCAL_PENDING",
  };
}

export function addAuthorizedWorker(
  draft: RdoDraft,
  collaboratorId: string,
  catalog: readonly RdoContextCollaborator[],
  createId: () => string = () => crypto.randomUUID(),
): RdoDraft {
  return {
    ...draft,
    maoObra: addAuthorizedWorkforceMember(
      draft.maoObra,
      collaboratorId,
      catalog,
      createId,
    ),
  };
}

export function setRosterSelected(
  draft: RdoDraft,
  localId: string,
  selected: boolean,
): RdoDraft {
  const target = draft.maoObra.find((item) => item.localId === localId);
  if (!target) return draft;
  if (selected && target.availability === "UNAVAILABLE") {
    throw new Error("Colaborador indisponível não pode ser selecionado.");
  }
  const detachedCollaboratorId = selected
    ? ""
    : target.colaboradorId.trim();
  const clearsApontador = !selected &&
    Boolean(detachedCollaboratorId) &&
    detachedCollaboratorId === draft.apontadorColaboradorId.trim();
  return {
    ...draft,
    maoObra: draft.maoObra.map((item) =>
      item.localId === localId ? { ...item, selected } : item,
    ),
    alocacoesColaboradores: detachedCollaboratorId
      ? draft.alocacoesColaboradores.filter(
          (item) =>
            item.colaboradorId.trim() !== detachedCollaboratorId,
        )
      : draft.alocacoesColaboradores,
    apontadorColaboradorId: clearsApontador
      ? ""
      : draft.apontadorColaboradorId,
    apontadorRdo: clearsApontador ? "" : draft.apontadorRdo,
  };
}

export function removeRosterMember(
  draft: RdoDraft,
  localId: string,
): RdoDraft {
  const target = draft.maoObra.find((item) => item.localId === localId);
  if (!target) return draft;
  const removedCollaboratorId = target.colaboradorId.trim();
  const clearsApontador =
    Boolean(removedCollaboratorId) &&
    removedCollaboratorId === draft.apontadorColaboradorId.trim();

  return {
    ...draft,
    maoObra: draft.maoObra.filter((item) => item.localId !== localId),
    alocacoesColaboradores: removedCollaboratorId
      ? draft.alocacoesColaboradores.filter(
          (item) =>
            item.colaboradorId.trim() !== removedCollaboratorId,
        )
      : draft.alocacoesColaboradores,
    apontadorColaboradorId: clearsApontador
      ? ""
      : draft.apontadorColaboradorId,
    apontadorRdo: clearsApontador ? "" : draft.apontadorRdo,
  };
}

export function setRosterApontador(
  draft: RdoDraft,
  collaboratorId: string,
): RdoDraft {
  if (!collaboratorId) {
    return { ...draft, apontadorColaboradorId: "", apontadorRdo: "" };
  }
  const row = draft.maoObra.find(
    (item) => item.colaboradorId === collaboratorId && item.selected,
  );
  if (!row) {
    throw new Error("O apontador deve estar selecionado na equipe do RDO.");
  }
  return {
    ...draft,
    apontadorColaboradorId: collaboratorId,
    apontadorRdo: row.nomeColaborador,
  };
}

/**
 * Sugere como apontador quem está preenchendo o RDO.
 *
 * <p>Quase sempre é a mesma pessoa, e deixar "Sem apontador" como padrão
 * transformava isso num campo a lembrar de preencher — esquecível justamente
 * por ser óbvio. A sugestão só entra quando ninguém foi escolhido ainda e a
 * pessoa está marcada na equipe do dia: fora disso o campo continua como
 * estava, e quem quiser trocar troca.
 *
 * <p>Não é imposição. Uma vez escolhido — inclusive "Sem apontador", que é uma
 * escolha — este caminho não mexe mais.
 */
export function sugerirApontador(
  draft: RdoDraft,
  colaboradorId: string,
): RdoDraft {
  const candidato = colaboradorId.trim();
  if (!candidato || draft.apontadorColaboradorId.trim()) {
    return draft;
  }
  const row = draft.maoObra.find(
    (item) => item.colaboradorId === candidato && item.selected,
  );
  if (!row) {
    return draft;
  }
  return {
    ...draft,
    apontadorColaboradorId: candidato,
    apontadorRdo: row.nomeColaborador,
  };
}

export function contextPresentation(
  context: RdoCreationContextLookup,
  now = new Date(),
): {
  status: RdoContextTruthStatus;
  receiptVersion: number;
  sourceVersion: number;
  label: string;
} {
  let status: RdoContextTruthStatus;
  if (context.freshness.status === "LOCAL_PENDING") {
    status = "LOCAL_PENDING";
  } else if (!isRdoCreationContextComplete(context)) {
    status = "PARTIAL";
  } else if (
    !Number.isFinite(Date.parse(context.freshness.staleAfter)) ||
    now.getTime() > Date.parse(context.freshness.staleAfter)
  ) {
    status = "STALE";
  } else {
    status = "FRESH";
  }
  return {
    status,
    receiptVersion: context.provenance.receiptVersion,
    sourceVersion: context.provenance.sourceVersion,
    label:
      status === "FRESH"
        ? "Atualizado"
        : status === "LOCAL_PENDING"
          ? "Local pendente"
          : status === "STALE"
            ? "Desatualizado"
            : "Parcial",
  };
}

export function shouldApplyRemoteContext(input: {
  isExisting: boolean;
  requestedKey: string;
  currentKey: string;
  revisionAtRequest: number;
  currentRevision: number;
}): boolean {
  return !input.isExisting &&
    input.requestedKey === input.currentKey &&
    input.revisionAtRequest === input.currentRevision;
}

export function nextRosterFocusIndex(
  key: string,
  current: number,
  length: number,
): number {
  if (length <= 0) return -1;
  if (key === "Home") return 0;
  if (key === "End") return length - 1;
  if (key === "ArrowDown") return (current + 1) % length;
  if (key === "ArrowUp") return (current - 1 + length) % length;
  return Math.min(Math.max(current, 0), length - 1);
}

export function selectedWorkforce(
  rows: readonly MaoObraDraft[],
): MaoObraDraft[] {
  return rows.filter((row) => row.selected);
}
