import { useId, useMemo, useState } from "react";

import "./DualListbox.css";

/**
 * Escolha de vários itens com os dois lados à vista.
 *
 * <p>A combinação anterior — buscar, escolher um, apertar "Adicionar", repetir —
 * cobra um ciclo inteiro por pessoa. Montar uma frente de doze é doze ciclos, e
 * em nenhum momento se vê ao mesmo tempo quem está dentro e quem falta. É a
 * pergunta que o apontador realmente faz ao começar o dia, e a tela não a
 * respondia.
 *
 * <p>Aqui os dois lados ficam visíveis e a seleção é múltipla: marca-se o que
 * for, move-se de uma vez. O caminho de volta é igual, porque tirar alguém da
 * equipe é tão comum quanto pôr — quem chegou por herança do RDO anterior
 * costuma precisar sair.
 *
 * <p>A busca filtra apenas o lado disponível. Filtrar os dois esconderia parte
 * de quem já está escolhido, e some da tela sem sair da seleção — o item
 * continuaria valendo sem aparecer, que é a forma mais silenciosa de perder
 * trabalho neste app.
 */

export interface DualListboxItem {
  id: string;
  titulo: string;
  detalhe?: string | null;
}

export interface DualListboxProps {
  rotulo: string;
  disponiveis: readonly DualListboxItem[];
  escolhidos: readonly DualListboxItem[];
  onEscolher: (ids: readonly string[]) => void;
  onRemover: (ids: readonly string[]) => void;
  /*
   * Os títulos dos painéis nomeiam as duas listas para quem navega por leitor de
   * tela, então precisam ser distintos entre dois seletores na mesma página —
   * "Na equipe" e "No RDO" na mesma tela, por exemplo.
   */
  tituloDisponiveis?: string;
  tituloEscolhidos?: string;
  /** Some quando não há nada de que escolher — obra sem catálogo, por exemplo. */
  mensagemSemDisponiveis?: string;
  mensagemSemEscolhidos?: string;
  desabilitado?: boolean;
}

function combina(item: DualListboxItem, alvo: string): boolean {
  if (!alvo) return true;
  return [item.titulo, item.detalhe]
    .filter(Boolean)
    .join(" ")
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLocaleLowerCase("pt-BR")
    .includes(alvo);
}

export function DualListbox({
  rotulo,
  disponiveis,
  escolhidos,
  onEscolher,
  onRemover,
  tituloDisponiveis = "Disponíveis",
  tituloEscolhidos = "Na equipe",
  mensagemSemDisponiveis = "Nada disponível.",
  mensagemSemEscolhidos = "Ninguém escolhido ainda.",
  desabilitado = false,
}: DualListboxProps) {
  const baseId = useId();
  const [busca, setBusca] = useState("");
  const [marcadosDisponiveis, setMarcadosDisponiveis] = useState<string[]>([]);
  const [marcadosEscolhidos, setMarcadosEscolhidos] = useState<string[]>([]);

  const filtrados = useMemo(() => {
    const alvo = busca
      .normalize("NFD")
      .replace(/\p{Diacritic}/gu, "")
      .trim()
      .toLocaleLowerCase("pt-BR");
    return disponiveis.filter((item) => combina(item, alvo));
  }, [disponiveis, busca]);

  function alternar(
    id: string,
    marcados: string[],
    definir: (proximos: string[]) => void,
  ) {
    definir(
      marcados.includes(id)
        ? marcados.filter((atual) => atual !== id)
        : [...marcados, id],
    );
  }

  /*
   * Só move o que ainda está visível. Marcar alguém, digitar na busca até ele
   * sumir e então mover levaria embora um item que não está mais na tela — a
   * pessoa veria acontecer algo que não pediu.
   */
  function escolherMarcados() {
    const visiveis = new Set(filtrados.map((item) => item.id));
    const mover = marcadosDisponiveis.filter((id) => visiveis.has(id));
    if (mover.length === 0) return;
    onEscolher(mover);
    setMarcadosDisponiveis([]);
  }

  function removerMarcados() {
    if (marcadosEscolhidos.length === 0) return;
    onRemover(marcadosEscolhidos);
    setMarcadosEscolhidos([]);
  }

  function painel(
    lado: "disponiveis" | "escolhidos",
    itens: readonly DualListboxItem[],
    marcados: string[],
    definir: (proximos: string[]) => void,
    vazio: string,
  ) {
    const tituloId = `${baseId}-${lado}-titulo`;
    return (
      <div className="dual-listbox__painel">
        <p className="dual-listbox__painel-titulo" id={tituloId}>
          {lado === "disponiveis" ? tituloDisponiveis : tituloEscolhidos}
          <span className="dual-listbox__contagem">{itens.length}</span>
        </p>
        <ul
          className="dual-listbox__lista"
          role="listbox"
          aria-multiselectable="true"
          aria-labelledby={tituloId}
        >
          {itens.length === 0 ? (
            <li className="dual-listbox__vazio">{vazio}</li>
          ) : (
            itens.map((item) => {
              const marcado = marcados.includes(item.id);
              return (
                <li key={item.id}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={marcado}
                    disabled={desabilitado}
                    className={
                      marcado
                        ? "dual-listbox__item is-marcado"
                        : "dual-listbox__item"
                    }
                    onClick={() => alternar(item.id, marcados, definir)}
                  >
                    <strong>{item.titulo}</strong>
                    {item.detalhe ? <span>{item.detalhe}</span> : null}
                  </button>
                </li>
              );
            })
          )}
        </ul>
      </div>
    );
  }

  return (
    <div className="dual-listbox" role="group" aria-label={rotulo}>
      <label className="dual-listbox__busca">
        Buscar
        <input
          value={busca}
          disabled={desabilitado}
          placeholder="Nome, código ou papel"
          onChange={(event) => setBusca(event.target.value)}
        />
      </label>

      <div className="dual-listbox__corpo">
        {painel(
          "disponiveis",
          filtrados,
          marcadosDisponiveis,
          setMarcadosDisponiveis,
          busca.trim()
            ? "Ninguém com esse termo."
            : mensagemSemDisponiveis,
        )}

        <div className="dual-listbox__acoes">
          <button
            type="button"
            disabled={desabilitado || marcadosDisponiveis.length === 0}
            onClick={escolherMarcados}
          >
            Adicionar
          </button>
          <button
            type="button"
            disabled={desabilitado || marcadosEscolhidos.length === 0}
            onClick={removerMarcados}
          >
            Remover
          </button>
        </div>

        {painel(
          "escolhidos",
          escolhidos,
          marcadosEscolhidos,
          setMarcadosEscolhidos,
          mensagemSemEscolhidos,
        )}
      </div>
    </div>
  );
}
