import { useEffect, useId, useMemo, useRef, useState } from "react";

import type { ColaboradorLocalRecord } from "../../lib/db/db.types";
import {
  MINIMO_PARA_BUSCAR,
  pessoasParaMostrar,
  proximoDestaque,
} from "./buscaDePessoas";

/**
 * Escolha de uma pessoa entre muitas.
 *
 * <p>Antes disto havia um {@code <select>} nativo com a lista inteira dentro.
 * Ele falhava de dois modos ao mesmo tempo. O visível: uma coluna de centenas
 * de nomes sem busca, em que achar alguém é rolar até encontrar. E o invisível,
 * que era o pior: o catálogo chegava cortado nos primeiros cinquenta nomes em
 * ordem alfabética, então quem se chamava Rita simplesmente não existia na
 * tela — sem aviso, sem lista vazia, sem nada que dissesse que faltava gente.
 * Uma lista truncada em silêncio se parece exatamente com uma lista completa.
 *
 * <p>Por isso a digitação aqui não filtra só o que já está carregado: ela
 * consulta. Filtrar localmente seria mais simples e continuaria escondendo
 * todo mundo depois do quinquagésimo nome — a correção precisa alcançar quem
 * nunca chegou ao dispositivo.
 */

export type SeletorDePessoaProps = {
  readonly pessoas: readonly ColaboradorLocalRecord[];
  readonly selecionadaId: string;
  readonly onSelecionar: (colaboradorId: string) => void;
  /**
   * Busca no servidor. Recebe o termo digitado; quem chama decide como mesclar
   * o resultado em {@code pessoas}. Ausente, o seletor filtra só o que tem.
   */
  readonly onBuscar?: (termo: string) => Promise<void>;
  readonly desabilitado?: boolean;
  readonly rotulo?: string;
};

/** Espera antes de consultar: tempo de terminar de digitar, não de pensar. */
const ESPERA_ATE_BUSCAR_MS = 300;

export function SeletorDePessoa({
  pessoas,
  selecionadaId,
  onSelecionar,
  onBuscar,
  desabilitado = false,
  rotulo = "Pessoa",
}: SeletorDePessoaProps) {
  const [termo, setTermo] = useState("");
  const [aberto, setAberto] = useState(false);
  const [destaque, setDestaque] = useState(-1);
  const [buscando, setBuscando] = useState(false);
  const idBase = useId();
  const listaId = `${idBase}-lista`;
  const containerRef = useRef<HTMLDivElement | null>(null);

  const selecionada = useMemo(
    () => pessoas.find((pessoa) => pessoa.id === selecionadaId) ?? null,
    [pessoas, selecionadaId],
  );

  const { pessoas: visiveis, excedeu } = useMemo(
    () => pessoasParaMostrar(pessoas, termo),
    [pessoas, termo],
  );

  /*
   * A consulta é adiada e cancelável. Sem o cancelamento, cada tecla deixaria
   * uma resposta a caminho e a última a chegar venceria — que não é
   * necessariamente a da última tecla. A lista passaria a mostrar o resultado
   * de um termo que a pessoa já apagou.
   */
  useEffect(() => {
    if (!onBuscar || termo.trim().length < MINIMO_PARA_BUSCAR) {
      return;
    }
    let cancelado = false;
    const relogio = setTimeout(() => {
      setBuscando(true);
      void onBuscar(termo.trim())
        .catch(() => {
          // Offline ou servidor fora: o que já está no dispositivo continua
          // valendo, e o filtro local segue respondendo.
        })
        .finally(() => {
          if (!cancelado) {
            setBuscando(false);
          }
        });
    }, ESPERA_ATE_BUSCAR_MS);
    return () => {
      cancelado = true;
      clearTimeout(relogio);
    };
  }, [termo, onBuscar]);

  // Fechar ao clicar fora: uma lista aberta por cima do resto do formulário
  // esconde os campos seguintes, e ninguém procura um botão para fechá-la.
  useEffect(() => {
    if (!aberto) {
      return;
    }
    function aoApontarFora(evento: MouseEvent) {
      if (
        containerRef.current &&
        !containerRef.current.contains(evento.target as Node)
      ) {
        setAberto(false);
      }
    }
    document.addEventListener("mousedown", aoApontarFora);
    return () => document.removeEventListener("mousedown", aoApontarFora);
  }, [aberto]);

  function escolher(pessoa: ColaboradorLocalRecord) {
    onSelecionar(pessoa.id);
    setTermo("");
    setAberto(false);
    setDestaque(-1);
  }

  function aoTeclar(evento: React.KeyboardEvent<HTMLInputElement>) {
    if (evento.key === "ArrowDown" || evento.key === "ArrowUp") {
      evento.preventDefault();
      setAberto(true);
      setDestaque((atual) =>
        proximoDestaque(
          atual,
          evento.key === "ArrowDown" ? 1 : -1,
          visiveis.length,
        ),
      );
      return;
    }
    if (evento.key === "Enter") {
      // Só intercepta o Enter quando há uma opção destacada. Caso contrário
      // ele pertence ao formulário, e roubá-lo impediria de salvar pelo
      // teclado.
      if (aberto && destaque >= 0 && visiveis[destaque]) {
        evento.preventDefault();
        escolher(visiveis[destaque]);
      }
      return;
    }
    if (evento.key === "Escape" && aberto) {
      evento.preventDefault();
      setAberto(false);
      setDestaque(-1);
    }
  }

  const descricaoDaEscolha = selecionada
    ? `${selecionada.nome}${
        selecionada.cpfMascarado ? ` · ${selecionada.cpfMascarado}` : ""
      }`
    : "";

  return (
    <div className="seletor-pessoa" ref={containerRef}>
      <label className="seletor-pessoa__rotulo" htmlFor={`${idBase}-campo`}>
        {rotulo}
      </label>

      {selecionada && !aberto ? (
        <div className="seletor-pessoa__escolhida">
          <span>{descricaoDaEscolha}</span>
          {!desabilitado && (
            <button
              type="button"
              onClick={() => {
                onSelecionar("");
                setAberto(true);
              }}
              aria-label={`Trocar ${selecionada.nome}`}
            >
              Trocar
            </button>
          )}
        </div>
      ) : (
        <input
          id={`${idBase}-campo`}
          className="seletor-pessoa__campo"
          type="text"
          role="combobox"
          autoComplete="off"
          aria-expanded={aberto}
          aria-controls={listaId}
          aria-autocomplete="list"
          aria-activedescendant={
            aberto && destaque >= 0 && visiveis[destaque]
              ? `${idBase}-opcao-${visiveis[destaque].id}`
              : undefined
          }
          disabled={desabilitado}
          placeholder="Digite o nome para buscar"
          value={termo}
          onChange={(evento) => {
            setTermo(evento.target.value);
            setAberto(true);
            setDestaque(-1);
          }}
          onFocus={() => setAberto(true)}
          onKeyDown={aoTeclar}
        />
      )}

      {aberto && (
        <div className="seletor-pessoa__painel">
          <ul className="seletor-pessoa__lista" id={listaId} role="listbox">
            {visiveis.map((pessoa, indice) => (
              <li
                key={pessoa.id}
                id={`${idBase}-opcao-${pessoa.id}`}
                role="option"
                aria-selected={pessoa.id === selecionadaId}
                className={
                  indice === destaque
                    ? "seletor-pessoa__opcao is-destacada"
                    : "seletor-pessoa__opcao"
                }
                onMouseDown={(evento) => {
                  // mousedown, não click: o blur do campo fecharia a lista
                  // antes de o clique chegar à opção.
                  evento.preventDefault();
                  escolher(pessoa);
                }}
                onMouseEnter={() => setDestaque(indice)}
              >
                <strong>{pessoa.nome}</strong>
                {pessoa.cpfMascarado && <small>{pessoa.cpfMascarado}</small>}
              </li>
            ))}
          </ul>

          {visiveis.length === 0 && (
            <p className="seletor-pessoa__aviso" role="status">
              {buscando
                ? "Procurando…"
                : termo.trim().length < MINIMO_PARA_BUSCAR
                  ? "Digite ao menos duas letras do nome."
                  : "Ninguém encontrado com esse nome."}
            </p>
          )}

          {/*
            O aviso que o seletor anterior não dava. Enquanto houver mais gente
            do que cabe, dizer isso é o que impede a lista de se passar por
            completa.
          */}
          {excedeu && (
            <p className="seletor-pessoa__aviso" role="status">
              Há mais pessoas do que cabe na lista — continue digitando para
              estreitar.
            </p>
          )}

          {buscando && visiveis.length > 0 && (
            <p className="seletor-pessoa__aviso" role="status">
              Procurando no cadastro…
            </p>
          )}
        </div>
      )}
    </div>
  );
}
