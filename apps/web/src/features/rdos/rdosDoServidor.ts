import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import { getCortexDb } from "../../lib/db/cortexDb";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import { buscarRdoAutoritativoPorId } from "./rdoLookupApi";
import { listCachedAuthorizedRdoWorksites } from "./rdoCreationContextRepository";
import { limparRastroLocalDoRdo } from "./rdoLifecycle";

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
 *   <li><b>Nunca apaga por inferência.</b> A ausência de um RDO na listagem
 *       não vira remoção local: uma lista incompleta — por filtro, por rede,
 *       por obra que saiu do escopo — levaria embora documentos que continuam
 *       existindo. O que a ausência vira é uma pergunta direta: o aparelho
 *       busca aquele RDO pelo id, e só o remove quando o servidor responde,
 *       nominalmente, que ele não existe mais. Sem essa pergunta, o RDO
 *       apagado numa máquina ficava imortal em todas as outras — o evento de
 *       apagamento já tinha passado pelo cursor delas antes de alguém saber
 *       tratá-lo, e nada mais o reentregava.</li>
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

/**
 * Quantos desaparecimentos cada passagem confirma no servidor, um a um.
 *
 * <p>Confirmar é uma requisição por RDO suspeito, e a suspeita nasce da
 * ausência na listagem — que em condição normal significa meia dúzia de
 * apagamentos, não centenas. O teto existe para o caso anormal: uma listagem
 * que veio manca faria toda a coleção local virar suspeita de uma vez, e sem
 * teto isso seria uma rajada de requisições num aparelho em campo. O que não
 * couber espera a passagem seguinte, ainda visível — errar para o lado de
 * mostrar demais é o erro barato.
 */
const CONFIRMACOES_DE_SUMICO_POR_PASSAGEM = 20;

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
 * O carimbo que faz o RDO apagado sumir da lista.
 *
 * <p>É `canceladoEm` — não o status — que a lista lê para esconder o que foi
 * apagado. A reconciliação trazia o status certo e o carimbo vazio, então todo
 * RDO apagado voltava para a primeira página a cada abertura, como se
 * estivesse vivo. Apagar tem de significar a mesma coisa nos dois caminhos.
 *
 * <p>O carimbo local prevalece quando existe, porque é o do cancelamento de
 * verdade. Faltando ele, vale o do resumo: a hora exata não vem na listagem, e
 * inventar "agora" faria um RDO apagado meses atrás parecer recém-apagado a
 * cada reconciliação. Restaurar limpa o carimbo, que é o caminho de volta.
 */
function carimboDeCancelamento(
  status: LocalRdoRecord["statusRdo"],
  local: LocalRdoRecord | undefined,
  resumo: RdoResumoRemoto,
  agora: string,
): string | null {
  if (status !== "CANCELADA") return null;
  return local?.canceladoEm ?? resumo.atualizadoEm ?? agora;
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
  const status = statusLocal(resumo.status);
  return {
    id: resumo.id,
    obraId: resumo.obraId,
    programacaoId: null,
    numeroRdo: resumo.numeroRdo,
    dataRdo: resumo.dataRdo,
    statusRdo: status,
    canceladoEm: carimboDeCancelamento(status, undefined, resumo, agora),
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
  /** RDOs que o servidor confirmou não existirem mais, tirados do aparelho. */
  removidos: number;
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
    removidos: 0,
  };
  const obras = await listCachedAuthorizedRdoWorksites().catch(() => []);
  if (obras.length === 0) return resultado;

  const agora = new Date().toISOString();
  const semConteudo: RdoResumoRemoto[] = [];
  let confirmacoesRestantes = CONFIRMACOES_DE_SUMICO_POR_PASSAGEM;

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
      /*
       * Falta conteúdo quando o registro nunca o teve, ou quando o servidor
       * diz que o documento mudou depois da última leitura deste aparelho.
       *
       * <p>A comparação é entre dois carimbos do servidor — o da listagem
       * agora e o guardado na última leitura. Antes ela comparava o carimbo do
       * servidor com o `updatedAt` local, que é escrito com o relógio deste
       * aparelho: num relógio adiantado, a cópia local parecia sempre mais
       * nova, e a edição feita por outra pessoa nunca era buscada.
       *
       * <p>Registro sem o carimbo do servidor é registro gravado antes desta
       * correção: busca-se o conteúdo uma vez, e a partir daí ele passa a ter.
       */
      const precisaDeConteudo =
        local === undefined ||
        local.versaoEntidade === null ||
        !local.servidorAtualizadoEm ||
        (remoto.atualizadoEm !== null &&
          remoto.atualizadoEm > local.servidorAtualizadoEm);
      if (precisaDeConteudo) semConteudo.push(remoto);
    }

    /*
     * O caminho de volta: o que este aparelho tem e o servidor já não lista.
     *
     * <p>A listagem desta obra chegou inteira — o `catch` acima já descartou a
     * que não chegou —, então um registro sincronizado que não aparece nela é
     * suspeito de ter sido apagado em outra máquina. Suspeito, não condenado:
     * a remoção só acontece depois de o servidor responder 404 para o id
     * exato. Um FOUND, um erro de rede ou um acesso negado deixam o registro
     * onde está, e o custo do engano é mostrar por mais uma passagem um RDO
     * que já morreu — o erro barato.
     *
     * <p>Isto também é o que limpa os aparelhos envenenados antes do evento
     * RDO_APAGADO ser tratado no pull: o evento já passou pelo cursor deles e
     * nada o reentrega, então sem esta pergunta o RDO apagado ficaria lá para
     * sempre, com o selo de sincronizado.
     */
    const idsNoServidor = new Set(remotos.map((remoto) => remoto.id));
    const locaisDaObra = await database.getAllFromIndex(
      "rdos",
      "by-obra-id",
      obra.id,
    );
    for (const local of locaisDaObra) {
      if (confirmacoesRestantes <= 0) break;
      if (local.syncStatus !== "SYNCED") continue;
      if (idsNoServidor.has(local.id)) continue;
      confirmacoesRestantes -= 1;
      try {
        const autoritativo = await buscarRdoAutoritativoPorId(local.id);
        if (autoritativo.kind !== "MISSING") continue;
        // Refeita de propósito: a confirmação é assíncrona, e alguém pode ter
        // começado a editar este RDO enquanto ela acontecia. Trabalho local
        // novo vale mais do que a ausência no servidor.
        const aindaLocal = await database.get("rdos", local.id);
        if (aindaLocal === undefined || aindaLocal.syncStatus !== "SYNCED") {
          continue;
        }
        await limparRastroLocalDoRdo(local.id);
        resultado.removidos += 1;
      } catch {
        // Sem confirmação não se apaga nada; fica para a próxima passagem.
      }
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

    const statusDetalhado = statusLocal(
      texto(autoritativo.rdo.status) || remoto.status,
    );
    await database.put("rdos", {
      ...(local ?? registroDoCabecalho(remoto, agora)),
      id: remoto.id,
      obraId: remoto.obraId,
      numeroRdo: remoto.numeroRdo || texto(autoritativo.rdo.numeroRdo),
      dataRdo: remoto.dataRdo,
      statusRdo: statusDetalhado,
      // Um RDO cancelado no servidor depois de este aparelho já o conhecer
      // chegava por aqui com o status novo e o carimbo antigo — vazio —, e
      // continuava na lista. Restaurado, o carimbo sai e ele volta.
      canceladoEm: carimboDeCancelamento(
        statusDetalhado, local, remoto, agora,
      ),
      programacaoId: texto(autoritativo.rdo.programacaoId) || null,
      syncStatus: "SYNCED",
      versaoEntidade: autoritativo.version,
      payload: autoritativo.rdo,
      updatedAt: remoto.atualizadoEm ?? agora,
      servidorAtualizadoEm: remoto.atualizadoEm ?? agora,
    } as LocalRdoRecord);
    resultado.detalhados += 1;
  }

  return resultado;
}
