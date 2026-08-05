import { DualListbox } from "../../components/DualListbox";
import {
  adicionarEquipamentosDoCatalogo,
  equipamentosDisponiveis,
  equipamentosEscolhidos,
  removerEquipamentos,
} from "./equipamentosDoRdo";
import type { EquipamentoDraft } from "./rdo.types";
import type { RdoContextEquipment } from "./rdoLookupApi";

interface RdoEquipmentPickerProps {
  parque: readonly RdoContextEquipment[];
  equipamentos: readonly EquipamentoDraft[];
  onChange: (equipamentos: EquipamentoDraft[]) => void;
}

/**
 * O parque da obra de um lado, o que já entrou no RDO do outro.
 *
 * <p>O contexto de criação sempre trouxe o parque da obra e a tela nunca o
 * usou: cada máquina começava como ficha em branco onde se digitava para
 * procurar no catálogo global. Quem aponta a frente já sabe quais máquinas
 * estão na obra; o que não sabia era quais já tinham sido lançadas hoje.
 */
export function RdoEquipmentPicker({
  parque,
  equipamentos,
  onChange,
}: RdoEquipmentPickerProps) {
  return (
    <DualListbox
      rotulo="Equipamentos do RDO"
      disponiveis={equipamentosDisponiveis(parque, equipamentos)}
      escolhidos={equipamentosEscolhidos(equipamentos)}
      onEscolher={(assetIds) =>
        onChange(
          adicionarEquipamentosDoCatalogo(equipamentos, assetIds, parque),
        )
      }
      onRemover={(localIds) =>
        onChange(removerEquipamentos(equipamentos, localIds))
      }
      tituloDisponiveis="No parque da obra"
      tituloEscolhidos="Neste RDO"
      mensagemSemDisponiveis={
        parque.length === 0
          ? "O parque desta obra ainda não foi carregado. Use “+ Adicionar” para lançar a máquina à mão."
          : "Todo o parque da obra já está neste RDO."
      }
      mensagemSemEscolhidos="Nenhum equipamento neste RDO ainda."
    />
  );
}
