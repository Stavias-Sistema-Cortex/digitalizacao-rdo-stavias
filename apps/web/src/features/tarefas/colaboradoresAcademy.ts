import { buscarColaboradores } from "../rdos/rdoLookupApi";
import {
  listColaboradoresLocais,
  mergeColaboradoresLocais,
} from "../../lib/db/colaboradorLocalRepository";
import type { ColaboradorLocalRecord } from "../../lib/db/db.types";

/**
 * Cadastro do Academy com cache local: online alimenta o
 * IndexedDB; offline o reconhecimento segue funcionando com
 * o que já foi visto neste dispositivo.
 */
export async function hidratarColaboradoresAcademy(
  query = "",
): Promise<ColaboradorLocalRecord[]> {
  const resultados = await buscarColaboradores(query);
  const cachedAt = new Date().toISOString();

  // Guarda também inativos (com a flag) para que uma
  // desativação no Academy corrija o cache local; a
  // listagem filtra por ativo na leitura.
  const records = resultados
    .filter((colaborador) =>
      Boolean(colaborador.nome?.trim()),
    )
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
