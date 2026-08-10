import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import { getCortexDb } from "../../lib/db/cortexDb";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import { buscarRdoAutoritativoPorId } from "./rdoLookupApi";
import { listCachedAuthorizedRdoWorksites } from "./rdoCreationContextRepository";

/**
 * Os RDOs que existem no servidor, trazidos para este aparelho.
 *
 * <p>A lista de RDOs lia só o IndexedDB local — e o banco local é por pessoa,
 * porque seu nome deriva de {@code {ownerId, escopo}}. Então cada aparelho só
 * enxergava o que ele próprio tinha criado. Um encarregado que abria a conta
 * dele, com acesso à obra e com os RDOs já sincronizados, via a tela vazia; e
 * não havia como ele descobrir o porquê, porque nada estava errado — nada
 * trazia os RDOs de volta.
 *
 * <p>Era a única coleção do Córtex nessa situação. Obras, geometrias e equipes
 * já reconciliavam com o servidor; o RDO ficou de fora, e o efeito só aparece
 * quando duas pessoas trabalham na mesma obra.
 *
 * <p>Duas regras governam a escrita, e as duas existem para proteger o
 * trabalho de campo:
 *
 * <ul>
 *   <li><b>Só toca no que já está sincronizado.</b> Registro pendente, local,
 *       em conflito ou com erro é trabalho de alguém que ainda não subiu.
 *       Sobrescrevê-lo com a versão do servidor apagaria o apontamento do dia.</li>
 *   <li><b>Nunca apaga.</b> Ao contrário da reconciliação de geometria, a
 *       ausência de um RDO na resposta não vira remoção local. Uma lista
 *       incompleta — por filtro, por página, por obra que saiu do escopo —
 *       levaria embora documentos que continuam existindo.</li>
 * </ul>
 */

/** O cabeçalho que a lista do servidor devolve por RDO. */
export interface RdoResumoRemoto {
  id: string;
  obraId: string;
  numeroRdo: string;
  dataRdo: string;
  status: string;
  atualizadoEm: string | null;
}

/**
 * Quantos detalhes cada passagem busca.
 *
 * <p>O cabeçalho vem em uma requisição por obra; o conteúdo, uma por RDO. Numa
 * obra com um ano de apontamento isso é trezentas requisições, e em campo, com
 * a rede que existe lá, essa rajada é pior do que a espera. O que sobra entra
 * na passagem seguinte — a lista já mostra o documento pelo cabeçalho enquanto
 * isso, então ninguém fica sem saber que ele existe.
 */
const DETALHES_POR_PASSAGEM = 40;

function texto(valor: unknown): string {
  return typeof valor === "string" ? valor.trim() : "";
}

function resumoValido(valor: unknown): RdoResumoRemoto | null {
  if (typeof valor !== "object" || valor === null) return null;
  const bruto = valor as Record<string, unknown>;
  const id = texto(bruto.id);
  const obraId = texto(bruto.obraId);
  const dataRdo = texto(bruto.dataRdo);
  if (!id || !obraId || !dataRdo) return null;
  return {
    id,
    obraId,
    numeroRdo: texto(bruto.numeroRdo),
    dataRdo,
    status: texto(bruto.status) || "RASCUNHO",
    atualizadoEm: texto(bruto.atualizadoEm) || null,
  };
}

export async function listarRdosDaObra(
  obraId: string,
): Promise<RdoResumoRemoto[]> {
  const response = await apiFetch(
    `/rdos?obraId=${encodeURIComponent(obraId)}`,
  );
  const corpo = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(corpo, response.status));
  }
  if (!Array.isArray(corpo)) return [];
  return corpo
    .map(resumoValido)
    .filter((item): item is RdoResumoRemoto => item !== null);
}

/**
 * O estado local de um RDO pode ser reescrito pelo servidor?
 *
 * <p>Só quando não há nada de local nele. Qualquer outro estado é trabalho que
 * ainda não subiu — e o servidor, por definição, não sabe dele.
 */
function podeReceberDoServidor(local: LocalRdoRecord | undefined): boolean {
  return local === undefined || local.syncStatus === "SYNCED";
}

function statusLocal(status: string): LocalRdoRecord["statusRdo"] {
  if (status === "ENVIADO") return "ENVIADO";
  if (status === "CANCELADA" || status === "CANCELADO") return "CANCELADA";
  return "RASCUNHO";
}

/**
 * O registro que o cabeçalho sozinho já sustenta.
 *
 * <p>Serve para o documento aparecer na lista antes de o conteúdo chegar. O
 * payload nasce com o que o cabeçalho afirma e nada mais: inventar coleções
 * vazias aqui faria a tela dizer "0 equipamentos" sobre um RDO que tem dez.
 */
function registroDoCabecalho(
  resumo: RdoResumoRemoto,
  agora: string,
): LocalRdoRecord {
  return {
    id: resumo.id,
    obraId: resumo.obraId,
    programacaoId: null,
    numeroRdo: resumo.numeroRdo,
    dataRdo: resumo.dataRdo,
    statusRdo: statusLocal(resumo.status),
    syncStatus: "SYNCED",
    // Sem versão: o conteúdo ainda não chegou, e declarar uma versão que não
    // foi conferida deixaria uma edição futura nascer sobre base falsa.
    versaoEntidade: null,
    payload: {
      id: resumo.id,
      obraId: resumo.obraId,
      numeroRdo: resumo.numeroRdo,
      dataRdo: resumo.dataRdo,
      status: resumo.status,
    },
    createdAt: resumo.atualizadoEm ?? agora,
    updatedAt: resumo.atualizadoEm ?? agora,
  };
}

export interface ReconciliacaoDeRdos {
  /** Cabeçalhos novos que passaram a existir neste aparelho. */
  descobertos: number;
  /** RDOs cujo conteúdo foi buscado nesta passagem. */
  detalhados: number;
  /** Quantos ainda esperam o conteúdo, para a passagem seguinte. */
  pendentes: number;
}

/**
 * Traz para este aparelho os RDOs das obras que a pessoa alcança.
 *
 * <p>Falha de rede não é erro desta função: ela devolve o que conseguiu. A
 * lista continua servindo o que já está no aparelho, que é o contrato do modo
 * offline — e a passagem seguinte tenta de novo.
 */
export async function reconciliarRdosDoServidor(): Promise<ReconciliacaoDeRdos> {
  const resultado: ReconciliacaoDeRdos = {
    descobertos: 0,
    detalhados: 0,
    pendentes: 0,
  };
  const obras = await listCachedAuthorizedRdoWorksites().catch(() => []);
  if (obras.length === 0) return resultado;

  const agora = new Date().toISOString();
  const semConteudo: RdoResumoRemoto[] = [];

  for (const obra of obras) {
    let remotos: RdoResumoRemoto[];
    try {
      remotos = await listarRdosDaObra(obra.id);
    } catch {
      // Obra que não respondeu não impede as outras: sem rede, ou sem acesso
      // que o servidor reconheça, o resto da varredura continua valendo.
      continue;
    }

    const database = await getCortexDb();
    for (const remoto of remotos) {
      const local = await database.get("rdos", remoto.id);
      if (!podeReceberDoServidor(local)) continue;
      if (local === undefined) {
        await database.put("rdos", registroDoCabecalho(remoto, agora));
        resultado.descobertos += 1;
      }
      // Falta conteúdo quando o registro nunca o teve, ou quando o servidor
      // diz que o documento mudou depois da última vez que este aparelho o leu.
      const precisaDeConteudo =
        local === undefined ||
        local.versaoEntidade === null ||
        (remoto.atualizadoEm !== null && remoto.atualizadoEm > local.updatedAt);
      if (precisaDeConteudo) semConteudo.push(remoto);
    }
  }

  // Os mais recentes primeiro: é o que alguém abre.
  semConteudo.sort((um, outro) => outro.dataRdo.localeCompare(um.dataRdo));
  const nestaPassagem = semConteudo.slice(0, DETALHES_POR_PASSAGEM);
  resultado.pendentes = semConteudo.length - nestaPassagem.length;

  for (const remoto of nestaPassagem) {
    let autoritativo;
    try {
      autoritativo = await buscarRdoAutoritativoPorId(remoto.id);
    } catch {
      continue;
    }
    if (autoritativo.kind !== "FOUND") continue;

    const database = await getCortexDb();
    const local = await database.get("rdos", remoto.id);
    // A checagem é refeita aqui de propósito: a busca é assíncrona, e alguém
    // pode ter começado a editar o RDO enquanto ela acontecia. Escrever por
    // cima agora apagaria uma edição que nasceu depois da decisão.
    if (!podeReceberDoServidor(local)) continue;

    await database.put("rdos", {
      ...(local ?? registroDoCabecalho(remoto, agora)),
      id: remoto.id,
      obraId: remoto.obraId,
      numeroRdo: remoto.numeroRdo || texto(autoritativo.rdo.numeroRdo),
      dataRdo: remoto.dataRdo,
      statusRdo: statusLocal(texto(autoritativo.rdo.status) || remoto.status),
      programacaoId: texto(autoritativo.rdo.programacaoId) || null,
      syncStatus: "SYNCED",
      versaoEntidade: autoritativo.version,
      payload: autoritativo.rdo,
      updatedAt: remoto.atualizadoEm ?? agora,
    } as LocalRdoRecord);
    resultado.detalhados += 1;
  }

  return resultado;
}
