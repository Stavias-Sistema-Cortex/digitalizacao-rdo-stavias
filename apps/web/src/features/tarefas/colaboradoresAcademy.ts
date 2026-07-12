import {
  buscarColaboradores,
  buscarColaboradoresDaObra,
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
 * Com {@code obraId}, usa o endpoint escopado por obra — que funciona para o
 * usuário Beta (o catálogo global é administrativo). Sem obra, mantém o
 * catálogo global (perfil administrativo).
 */
export async function hidratarColaboradoresAcademy(
  query = "",
  obraId?: string,
): Promise<ColaboradorLocalRecord[]> {
  const cachedAt = new Date().toISOString();

  const records: ColaboradorLocalRecord[] = obraId
    ? (await buscarColaboradoresDaObra(obraId))
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
