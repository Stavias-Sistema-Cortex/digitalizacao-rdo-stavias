import type { DualListboxItem } from "../../components/DualListbox";
import { createEmptyEquipamento } from "./createEmptyRdo";
import type { EquipamentoDraft } from "./rdo.types";
import type { RdoContextEquipment } from "./rdoLookupApi";

/**
 * O maquinário da obra visto pelos dois lados.
 *
 * <p>O contexto de criação já trazia o parque de equipamentos da obra, e a tela
 * nunca o usava: cada máquina era uma ficha em branco onde se digitava para
 * buscar no catálogo global. Quem aponta a frente sabe quais máquinas estão na
 * obra — o que não sabe é quais já foram lançadas no RDO de hoje, e era
 * exatamente isso que a tela não mostrava.
 */

type IdFactory = () => string;

function tituloDoCatalogo(item: RdoContextEquipment): string {
  return item.nome?.trim() ||
    item.codigoExterno?.trim() ||
    "Equipamento sem nome";
}

function detalhe(partes: readonly (string | null | undefined)[], titulo: string) {
  const limpas = partes
    .map((parte) => parte?.trim() ?? "")
    .filter((parte) => parte && parte !== titulo);
  return limpas.length > 0 ? [...new Set(limpas)].join(" · ") : null;
}

/**
 * O que a obra tem e ainda não está no RDO. O corte é pelo assetId: uma máquina
 * lançada à mão, sem cadastro, não tem como esconder nada do catálogo.
 */
export function equipamentosDisponiveis(
  catalogo: readonly RdoContextEquipment[],
  noRdo: readonly EquipamentoDraft[],
): DualListboxItem[] {
  const lancados = new Set(
    noRdo.map((item) => item.assetId.trim()).filter(Boolean),
  );
  return catalogo
    .filter((item) => !lancados.has(item.id))
    .map((item) => {
      const titulo = tituloDoCatalogo(item);
      return {
        id: item.id,
        titulo,
        detalhe: detalhe([item.codigoExterno, item.categoria], titulo),
      };
    });
}

/**
 * O lado escolhido é indexado pelo localId, e não pelo assetId, para que a
 * máquina somada à mão — que não tem cadastro — apareça e possa sair.
 */
export function equipamentosEscolhidos(
  noRdo: readonly EquipamentoDraft[],
): DualListboxItem[] {
  return noRdo.map((item) => {
    const titulo = item.descricao.trim() ||
      item.prefixo.trim() ||
      "Equipamento sem identificação";
    return {
      id: item.localId,
      titulo,
      detalhe: detalhe([item.prefixo, item.tipoEquipamento], titulo),
    };
  });
}

export function adicionarEquipamentosDoCatalogo(
  noRdo: readonly EquipamentoDraft[],
  assetIds: readonly string[],
  catalogo: readonly RdoContextEquipment[],
  createId: IdFactory = () => crypto.randomUUID(),
): EquipamentoDraft[] {
  const porId = new Map(catalogo.map((item) => [item.id, item]));
  const lancados = new Set(
    noRdo.map((item) => item.assetId.trim()).filter(Boolean),
  );
  const novos: EquipamentoDraft[] = [];

  for (const assetId of assetIds) {
    const asset = porId.get(assetId);
    if (!asset) {
      throw new Error("Equipamento não pertence ao parque desta obra.");
    }
    if (lancados.has(assetId)) continue;
    lancados.add(assetId);
    novos.push({
      ...createEmptyEquipamento(),
      localId: createId(),
      assetId,
      prefixo: asset.codigoExterno?.trim() ?? "",
      descricao: asset.nome?.trim() ?? "",
      tipoEquipamento: asset.categoria?.trim() ?? "",
    });
  }

  return novos.length > 0 ? [...noRdo, ...novos] : [...noRdo];
}

export function removerEquipamentos(
  noRdo: readonly EquipamentoDraft[],
  localIds: readonly string[],
): EquipamentoDraft[] {
  const saindo = new Set(localIds);
  return noRdo.filter((item) => !saindo.has(item.localId));
}
