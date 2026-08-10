import { Fragment, useId, useMemo, useState } from "react";

import "./ListaDeMarcar.css";

/**
 * Uma lista só, com uma caixa de marcar por linha.
 *
 * <p>Antes disto havia dois painéis lado a lado e botões para mover itens entre
 * eles. Era melhor que a busca-e-adiciona que veio antes, mas continuava
 * pedindo que se entendesse um mecanismo — dois lados, dois sentidos, uma
 * seleção intermediária — para responder a uma pergunta de uma palavra: quem
 * trabalhou hoje.
 *
 * <p>A resposta é marcar. Quem está na obra aparece; quem trabalhou fica
 * marcado. Não há lado para onde mover, não há estado intermediário, e o que
 * está na tela é exatamente o que vai no RDO. Desmarcar não apaga a linha, o
 * que importa para quem veio herdado do RDO anterior: a procedência continua
 * gravada mesmo quando a pessoa não trabalhou hoje.
 *
 * <p>A busca filtra a lista inteira, e é seguro que filtre: marcado ou não, o
 * estado de cada linha é a caixa, não a presença na tela. Nada some da seleção
 * por sumir da vista.
 */

export interface ItemDeMarcar {
  id: string;
  titulo: string;
  detalhe?: string | null;
  marcado: boolean;
  /** Impede a marcação e diz por quê, sem esconder a linha. */
  impedimento?: string | null;
  /** Observação secundária — "já apontado em outro RDO", por exemplo. */
  aviso?: string | null;
  /** Só quem foi somado à mão pode sair de vez; o resto se desmarca. */
  removivel?: boolean;
  /**
   * Cabeçalho que separa esta linha das anteriores.
   *
   * <p>Existe para a lista que ficou grande demais para ser lida de cima a
   * baixo — a de pessoas, que passou a trazer o quadro inteiro da empresa. Sem
   * a separação, achar o ajudante da própria frente custaria rolar por gente de
   * outra obra. A busca continua alcançando todos os grupos.
   */
  grupo?: string | null;
}

export interface ListaDeMarcarProps {
  rotulo: string;
  itens: readonly ItemDeMarcar[];
  onMarcar: (id: string, marcado: boolean) => void;
  onRemover?: (id: string) => void;
  mensagemVazia?: string;
  desabilitado?: boolean;
  /** A busca só aparece quando a lista é grande o bastante para justificá-la. */
  minimoParaBuscar?: number;
}

function combina(item: ItemDeMarcar, alvo: string): boolean {
  if (!alvo) return true;
  return [item.titulo, item.detalhe]
    .filter(Boolean)
    .join(" ")
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLocaleLowerCase("pt-BR")
    .includes(alvo);
}

export function ListaDeMarcar({
  rotulo,
  itens,
  onMarcar,
  onRemover,
  mensagemVazia = "Nada para marcar aqui ainda.",
  desabilitado = false,
  minimoParaBuscar = 8,
}: ListaDeMarcarProps) {
  const buscaId = useId();
  const [busca, setBusca] = useState("");
  const alvo = busca
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .trim()
    .toLocaleLowerCase("pt-BR");

  const visiveis = useMemo(
    () => itens.filter((item) => combina(item, alvo)),
    [itens, alvo],
  );
  const marcados = itens.filter((item) => item.marcado).length;

  return (
    <div className="lista-de-marcar">
      <div className="lista-de-marcar__cabecalho">
        <span className="lista-de-marcar__contagem" role="status">
          {marcados} de {itens.length} {marcados === 1 ? "marcado" : "marcados"}
        </span>
        {itens.length >= minimoParaBuscar ? (
          <label className="lista-de-marcar__busca" htmlFor={buscaId}>
            <span>Procurar</span>
            <input
              id={buscaId}
              type="search"
              value={busca}
              disabled={desabilitado}
              onChange={(evento) => setBusca(evento.target.value)}
              placeholder="Nome, prefixo ou função"
            />
          </label>
        ) : null}
      </div>

      {visiveis.length === 0 ? (
        <p className="lista-de-marcar__vazia" role="status">
          {alvo ? "Nada encontrado com esse texto." : mensagemVazia}
        </p>
      ) : (
        <ul className="lista-de-marcar__itens" aria-label={rotulo}>
          {visiveis.map((item, indice) => (
            <Fragment key={item.id}>
              {item.grupo && item.grupo !== visiveis[indice - 1]?.grupo ? (
                <li className="lista-de-marcar__grupo" aria-hidden="true">
                  {item.grupo}
                </li>
              ) : null}
            <li
              className={
                item.marcado
                  ? "lista-de-marcar__item lista-de-marcar__item--marcado"
                  : "lista-de-marcar__item"
              }
            >
              <label>
                <input
                  type="checkbox"
                  checked={item.marcado}
                  disabled={desabilitado || Boolean(item.impedimento)}
                  onChange={(evento) =>
                    onMarcar(item.id, evento.target.checked)
                  }
                />
                <span className="lista-de-marcar__texto">
                  <strong>{item.titulo}</strong>
                  {item.detalhe ? <small>{item.detalhe}</small> : null}
                  {item.impedimento ? (
                    <small className="lista-de-marcar__impedimento">
                      {item.impedimento}
                    </small>
                  ) : null}
                  {item.aviso ? (
                    <small className="lista-de-marcar__aviso">
                      {item.aviso}
                    </small>
                  ) : null}
                </span>
              </label>
              {item.removivel && onRemover ? (
                <button
                  type="button"
                  className="lista-de-marcar__remover"
                  aria-label={`Remover ${item.titulo}`}
                  disabled={desabilitado}
                  onClick={() => onRemover(item.id)}
                >
                  Remover
                </button>
              ) : null}
            </li>
            </Fragment>
          ))}
        </ul>
      )}
    </div>
  );
}
