import { useId, useMemo, useState } from "react";

import { ListaDeMarcar, type ItemDeMarcar } from "../../components/ListaDeMarcar";
import { avisoDeApontamentoRepetido } from "./apontadosEmOutroRdo";
import { createEmptyEquipamento } from "./createEmptyRdo";
import {
  adicionarEquipamentosDoCatalogo,
  removerEquipamentos,
} from "./equipamentosDoRdo";
import type { EquipamentoDraft } from "./rdo.types";
import type { RdoContextEquipment } from "./rdoLookupApi";

interface RdoEquipmentPickerProps {
  parque: readonly RdoContextEquipment[];
  equipamentos: readonly EquipamentoDraft[];
  /** assetId → RDO do mesmo dia em que a máquina já foi apontada. */
  jaApontados?: ReadonlyMap<string, string>;
  onChange: (equipamentos: EquipamentoDraft[]) => void;
}

const PREFIXO_DO_PARQUE = "parque:";

function tituloDoCatalogo(item: RdoContextEquipment): string {
  return (
    item.nome?.trim() || item.codigoExterno?.trim() || "Equipamento sem nome"
  );
}

function detalhe(partes: readonly (string | null | undefined)[]): string | null {
  const limpas = partes
    .map((parte) => parte?.trim() ?? "")
    .filter(Boolean);
  return limpas.length > 0 ? [...new Set(limpas)].join(" · ") : null;
}

/**
 * O parque da obra numa lista de marcar, e o de terceiro atrás de um botão.
 *
 * <p>Cada máquina era uma ficha em branco com asset, prefixo, descrição, tipo,
 * vínculo, quantidade, início, fim e observações — nove campos para dizer que a
 * retroescavadeira da obra trabalhou hoje. O parque da obra já vinha no
 * contexto de criação e a tela não o usava.
 *
 * <p>Agora ele é a lista, e marcar basta. A entrada à mão continua existindo,
 * porque a máquina de terceiro não está no Zeladoria e o dia não pode esperar
 * pelo cadastro — mas ela é a exceção, e fica escondida até alguém pedir por
 * ela.
 */
export function RdoEquipmentPicker({
  parque,
  equipamentos,
  jaApontados,
  onChange,
}: RdoEquipmentPickerProps) {
  const [somandoAMao, setSomandoAMao] = useState(false);
  const [terceiro, setTerceiro] = useState({
    prefixo: "",
    descricao: "",
    tipoEquipamento: "",
  });
  const prefixoId = useId();
  const descricaoId = useId();
  const tipoId = useId();

  const noRdo = useMemo(
    () =>
      new Set(
        equipamentos.map((item) => item.assetId.trim()).filter(Boolean),
      ),
    [equipamentos],
  );

  const itens = useMemo<ItemDeMarcar[]>(() => {
    const lancados = equipamentos.map((item) => ({
      id: item.localId,
      titulo:
        item.descricao.trim() ||
        item.prefixo.trim() ||
        "Equipamento sem identificação",
      detalhe: detalhe([item.prefixo, item.tipoEquipamento]),
      marcado: true,
      aviso: avisoDeApontamentoRepetido(jaApontados?.get(item.assetId.trim())),
      // Tudo que está no RDO sai desmarcando: equipamento não guarda
      // procedência de RDO anterior como a mão de obra guarda, então tirar a
      // marca e tirar a linha são a mesma coisa.
      removivel: false,
    }));

    const disponiveis = parque
      .filter((item) => !noRdo.has(item.id))
      .map((item) => ({
        id: `${PREFIXO_DO_PARQUE}${item.id}`,
        titulo: tituloDoCatalogo(item),
        detalhe: detalhe([item.codigoExterno, item.categoria]),
        marcado: false,
        aviso: avisoDeApontamentoRepetido(jaApontados?.get(item.id)),
      }));

    return [...lancados, ...disponiveis];
  }, [equipamentos, parque, noRdo, jaApontados]);

  function marcar(id: string, marcado: boolean) {
    if (id.startsWith(PREFIXO_DO_PARQUE)) {
      if (!marcado) return;
      onChange(
        adicionarEquipamentosDoCatalogo(
          equipamentos,
          [id.slice(PREFIXO_DO_PARQUE.length)],
          parque,
        ),
      );
      return;
    }
    if (!marcado) onChange(removerEquipamentos(equipamentos, [id]));
  }

  function adicionarTerceiro() {
    const descricao = terceiro.descricao.trim() || terceiro.prefixo.trim();
    if (!descricao) return;
    onChange([
      ...equipamentos,
      {
        ...createEmptyEquipamento(),
        prefixo: terceiro.prefixo.trim(),
        // A exportação recusa equipamento sem descrição; cair no prefixo evita
        // que a máquina suma do RDO por um campo que ninguém viu em branco.
        descricao,
        tipoEquipamento: terceiro.tipoEquipamento.trim(),
        tipoVinculo: "TERCEIRIZADO",
      },
    ]);
    setTerceiro({ prefixo: "", descricao: "", tipoEquipamento: "" });
    setSomandoAMao(false);
  }

  return (
    <div className="rdo-equipment-picker">
      <ListaDeMarcar
        rotulo="Equipamentos do RDO"
        itens={itens}
        onMarcar={marcar}
        mensagemVazia={
          parque.length === 0
            ? "O parque desta obra ainda não foi carregado. Use “Adicionar equipamento” para lançar a máquina à mão."
            : "Nenhum equipamento nesta obra ainda."
        }
      />

      {somandoAMao ? (
        <form
          className="rdo-equipment-terceiro"
          onSubmit={(event) => {
            event.preventDefault();
            adicionarTerceiro();
          }}
        >
          <p className="rdo-equipment-terceiro__nota">
            Para a máquina que não está no Zeladoria — de terceiro, alugada de
            última hora. Ela entra neste RDO e não vira cadastro.
          </p>
          <div className="form-grid">
            <label htmlFor={prefixoId}>
              Prefixo ou placa
              <input
                id={prefixoId}
                maxLength={80}
                autoFocus
                value={terceiro.prefixo}
                onChange={(event) =>
                  setTerceiro((atual) => ({
                    ...atual,
                    prefixo: event.target.value,
                  }))
                }
              />
            </label>
            <label htmlFor={descricaoId}>
              Descrição
              <input
                id={descricaoId}
                maxLength={160}
                value={terceiro.descricao}
                onChange={(event) =>
                  setTerceiro((atual) => ({
                    ...atual,
                    descricao: event.target.value,
                  }))
                }
              />
            </label>
            <label htmlFor={tipoId}>
              Tipo
              <input
                id={tipoId}
                maxLength={80}
                value={terceiro.tipoEquipamento}
                onChange={(event) =>
                  setTerceiro((atual) => ({
                    ...atual,
                    tipoEquipamento: event.target.value,
                  }))
                }
              />
            </label>
          </div>
          <div className="rdo-equipment-terceiro__acoes">
            <button
              type="submit"
              className="add-button"
              disabled={
                !terceiro.descricao.trim() && !terceiro.prefixo.trim()
              }
            >
              Adicionar ao RDO
            </button>
            <button
              type="button"
              onClick={() => {
                setSomandoAMao(false);
                setTerceiro({ prefixo: "", descricao: "", tipoEquipamento: "" });
              }}
            >
              Cancelar
            </button>
          </div>
        </form>
      ) : (
        <button
          type="button"
          className="add-button"
          onClick={() => setSomandoAMao(true)}
        >
          Adicionar equipamento de terceiro
        </button>
      )}
    </div>
  );
}
