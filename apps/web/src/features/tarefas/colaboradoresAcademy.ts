import {
  buscarColaboradores,
  buscarColaboradoresDaObra,
  buscarColaboradoresParaEquipe,
} from "../rdos/rdoLookupApi";
import {
  listColaboradoresLocais,
  mergeColaboradoresLocais,
} from "../../lib/db/colaboradorLocalRepository";
import type { ColaboradorLocalRecord } from "../../lib/db/db.types";

/**
 * Cadastro do Academy com cache local: online alimenta o
 * IndexedDB; offline o reconhecimento segue funcionando com
 * o que já foi visto neste dispositivo.
 *
 * Com {@code obraId}, fala pelos endpoints da obra — que funcionam para o Beta,
 * já que o catálogo global é administrativo. Sem obra, usa o catálogo global.
 *
 * Dentro do escopo da obra há dois modos, e a diferença entre eles é o que
 * destrava formar equipe: sem termo, a lista de quem já pertence à obra, para a
 * tela abrir povoada; com termo, uma busca nominal que alcança quem ainda não
 * pertence. Sem o segundo, as duas pontas se travavam — para entrar na equipe
 * da obra era preciso já estar na obra, e a equipe é justamente a porta de
 * entrada.
 */
export async function hidratarColaboradoresAcademy(
  query = "",
  obraId?: string,
): Promise<ColaboradorLocalRecord[]> {
  const cachedAt = new Date().toISOString();

  const escopada = obraId
    ? // Com termo, procura em todo o cadastro pela obra — é assim que quem
      // monta a frente alcança alguém que ainda não pertence a ela. Sem termo,
      // a lista de quem já está: a tela abre povoada, sem varrer o cadastro.
      query.trim()
      ? await buscarColaboradoresParaEquipe(obraId, query)
      : await buscarColaboradoresDaObra(obraId)
    : null;

  const records: ColaboradorLocalRecord[] = escopada
    ? escopada
        .filter((colaborador) => Boolean(colaborador.nome?.trim()))
        .map((colaborador) => ({
          id: colaborador.id,
          nome: colaborador.nome?.trim() ?? "",
          cpfMascarado: colaborador.cpfMascarado,
          nomePerfil: colaborador.nomePerfil,
          ativo: true,
          updatedAt: null,
          cachedAt,
        }))
    : // Guarda também inativos (com a flag) para que uma
      // desativação no Academy corrija o cache local; a
      // listagem filtra por ativo na leitura.
      (await buscarColaboradores(query))
        .filter((colaborador) => Boolean(colaborador.nome?.trim()))
        .map((colaborador) => ({
          id: colaborador.id,
          nome: colaborador.nome?.trim() ?? "",
          cpfMascarado: colaborador.cpfMascarado,
          nomePerfil: colaborador.nomePerfil,
          ativo: colaborador.ativo,
          updatedAt: colaborador.atualizadoEm,
          cachedAt,
        }));

  await mergeColaboradoresLocais(records);

  return records;
}

export async function listarColaboradoresConhecidos(): Promise<
  ColaboradorLocalRecord[]
> {
  const locais = await listColaboradoresLocais();
  return locais.filter((colaborador) => colaborador.ativo);
}
