import { useId, useMemo, useState } from "react";

import { ListaDeMarcar, type ItemDeMarcar } from "../../components/ListaDeMarcar";
import {
  addAuthorizedWorker,
  removeRosterMember,
  setRosterApontador,
  setRosterSelected,
} from "./rdoCreationContext";
import { avisoDeApontamentoRepetido } from "./apontadosEmOutroRdo";
import { createEmptyMaoObra } from "./createEmptyRdo";
import type { MaoObraDraft, RdoDraft } from "./rdo.types";
import type { RdoContextCollaborator } from "./rdoLookupApi";

import "./RdoWorkforceEditor.css";

interface RdoWorkforceEditorProps {
  draft: RdoDraft;
  collaborators: readonly RdoContextCollaborator[];
  catalogUnavailableMessage?: string;
  sourceRdoNumber: string | null;
  /** colaboradorId → RDO do mesmo dia em que a pessoa já foi apontada. */
  jaApontados?: ReadonlyMap<string, string>;
  onChange: (draft: RdoDraft) => void;
}

/** Marca uma pessoa do catálogo que ainda não tem linha no rascunho. */
const PREFIXO_DO_CATALOGO = "catalogo:";

function nomeDaPessoa(row: MaoObraDraft): string {
  return row.nomeColaborador.trim() || row.colaboradorId || "Sem nome";
}

function nomeDoCatalogo(collaborator: RdoContextCollaborator): string {
  return (
    collaborator.nome?.trim() ||
    collaborator.codigoColaborador?.trim() ||
    "Colaborador sem nome"
  );
}

/**
 * A mão de obra do dia, marcada numa lista só.
 *
 * <p>Esta seção pedia, por pessoa, função, vínculo, quantidade, hora de início,
 * hora de fim e observações — sete campos numa tabela larga, ao lado de dois
 * painéis para mover gente de um lado para o outro. Uma frente de doze pessoas
 * eram oitenta e quatro caixas, quase todas repetindo o mesmo valor, digitadas
 * num celular à beira da pista.
 *
 * <p>A pergunta é uma só: quem trabalhou hoje. A função vem do cadastro, que é
 * quem a conhece; o horário do dia é o do RDO, que já está declarado na
 * Identificação; e o resto não era preenchido por ninguém. O que ficou é uma
 * lista de quem está na obra, com uma caixa para marcar — e quem foi somado à
 * mão, para o ajudante do dia que não tem cadastro.
 */
export function RdoWorkforceEditor({
  draft,
  collaborators,
  catalogUnavailableMessage,
  sourceRdoNumber,
  jaApontados,
  onChange,
}: RdoWorkforceEditorProps) {
  const [newCollaboratorName, setNewCollaboratorName] = useState("");
  const [somandoAMao, setSomandoAMao] = useState(false);
  const newCollaboratorId = useId();

  const noRascunho = useMemo(
    () =>
      new Set(
        draft.maoObra
          .map((row) => row.colaboradorId.trim())
          .filter(Boolean),
      ),
    [draft.maoObra],
  );

  /*
   * Uma lista só: primeiro quem já tem linha no rascunho — herdado do RDO
   * anterior, clonado ou somado à mão —, depois quem está autorizado na obra e
   * ainda não foi trazido. A ordem importa: quem o RDO já conhece fica em cima,
   * porque é sobre eles que a pergunta do dia costuma ser.
   */
  const itens = useMemo<ItemDeMarcar[]>(() => {
    const doRascunho = draft.maoObra.map((row) => {
      const indisponivel = row.availability === "UNAVAILABLE";
      return {
        id: row.localId,
        titulo: nomeDaPessoa(row),
        detalhe: row.cargo.trim() || null,
        grupo: "Neste RDO",
        marcado: row.selected && !indisponivel,
        impedimento: indisponivel
          ? "Sem cadastro ativo"
          : null,
        aviso: avisoDeApontamentoRepetido(
          jaApontados?.get(row.colaboradorId.trim()),
        ),
        // Só sai de vez quem foi somado à mão. Quem veio do catálogo ou do RDO
        // anterior se desmarca: a linha guarda a procedência, e apagá-la
        // perderia de onde a pessoa entrou neste RDO.
        removivel: !row.colaboradorId.trim(),
      };
    });

    /*
     * A lista traz o quadro inteiro da empresa, vindo do cadastro que o
     * Academy alimenta. Quem está ligado a esta obra vem primeiro; o resto vem
     * depois, atrás de um cabeçalho que diz o que é. Sem essa separação, achar
     * o ajudante da própria frente custaria rolar por gente de outra obra.
     *
     * <p>Contexto guardado antes desta versão não tem `naObra`. Ler a ausência
     * como "está na obra" é o que preserva o significado antigo: naquele
     * contexto, todo mundo que aparecia estava mesmo vinculado.
     */
    const doCatalogo = collaborators
      .filter((collaborator) => !noRascunho.has(collaborator.id))
      .map((collaborator) => ({
        id: `${PREFIXO_DO_CATALOGO}${collaborator.id}`,
        titulo: nomeDoCatalogo(collaborator),
        detalhe:
          [
            collaborator.codigoColaborador,
            collaborator.papelNaObra,
            collaborator.nomePerfil,
          ]
            .filter(Boolean)
            .join(" · ") || null,
        grupo:
          collaborator.naObra === false
            ? "Em outras obras"
            : "Nesta obra",
        marcado: false,
        aviso: avisoDeApontamentoRepetido(jaApontados?.get(collaborator.id)),
      }));

    return [
      ...doRascunho,
      ...doCatalogo.filter((item) => item.grupo === "Nesta obra"),
      ...doCatalogo.filter((item) => item.grupo !== "Nesta obra"),
    ];
  }, [draft.maoObra, collaborators, noRascunho, jaApontados]);

  const selecionados = draft.maoObra.filter(
    (row) =>
      row.selected &&
      row.availability !== "UNAVAILABLE" &&
      row.colaboradorId.trim(),
  );

  function marcar(id: string, marcado: boolean) {
    if (id.startsWith(PREFIXO_DO_CATALOGO)) {
      if (!marcado) return;
      onChange(
        addAuthorizedWorker(
          draft,
          id.slice(PREFIXO_DO_CATALOGO.length),
          collaborators,
        ),
      );
      return;
    }
    onChange(setRosterSelected(draft, id, marcado));
  }

  function adicionarAMao() {
    const nome = newCollaboratorName.trim().replace(/\s+/g, " ");
    if (!nome) return;

    onChange({
      ...draft,
      maoObra: [
        ...draft.maoObra,
        {
          ...createEmptyMaoObra(),
          nomeColaborador: nome,
          availability: "AVAILABLE",
          selected: true,
        },
      ],
    });
    setNewCollaboratorName("");
  }

  return (
    <section
      className="form-card rdo-workforce-editor"
      aria-labelledby="rdo-workforce-title"
    >
      <div className="section-heading collection-heading">
        <div>
          <h2 id="rdo-workforce-title">Mão de obra</h2>
        </div>
        <p>
          {sourceRdoNumber
            ? `Marque quem trabalhou hoje. A lista veio do RDO ${sourceRdoNumber} e do quadro de pessoal.`
            : "Marque quem trabalhou hoje. A lista é o quadro de pessoal, com quem está nesta obra em primeiro lugar."}
        </p>
      </div>

      <ListaDeMarcar
        rotulo="Pessoas do RDO"
        itens={itens}
        onMarcar={marcar}
        onRemover={(localId) => onChange(removeRosterMember(draft, localId))}
        desabilitado={Boolean(catalogUnavailableMessage)}
        mensagemVazia={
          catalogUnavailableMessage
            ? "Nenhum colaborador autorizado carregado."
            : "Nenhum colaborador carregado ainda. Use “Somar alguém à mão” para lançar quem trabalhou hoje."
        }
      />

      {catalogUnavailableMessage ? (
        <p className="rdo-workforce-catalog-unavailable" role="status">
          {catalogUnavailableMessage}
        </p>
      ) : null}

      <div className="rdo-workforce-controls">
        <label>
          Apontador do RDO
          <select
            value={draft.apontadorColaboradorId}
            onChange={(event) =>
              onChange(setRosterApontador(draft, event.target.value))
            }
          >
            <option value="">Sem apontador</option>
            {selecionados.map((row) => (
              <option key={row.colaboradorId} value={row.colaboradorId}>
                {nomeDaPessoa(row)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {/* Somar alguém à mão fica atrás de um botão: é a exceção — o ajudante
          do dia, o motorista de terceiro —, e deixá-la aberta punha uma caixa
          de texto vazia à frente da lista que responde a pergunta comum. */}
      {somandoAMao ? (
        <form
          className="rdo-workforce-manual-add"
          onSubmit={(event) => {
            event.preventDefault();
            adicionarAMao();
          }}
        >
          <label htmlFor={newCollaboratorId}>
            Nome de quem não está na lista
          </label>
          <input
            id={newCollaboratorId}
            maxLength={255}
            autoFocus
            value={newCollaboratorName}
            onChange={(event) => setNewCollaboratorName(event.target.value)}
          />
          <button
            type="submit"
            className="add-button"
            disabled={!newCollaboratorName.trim()}
          >
            Adicionar
          </button>
          <button
            type="button"
            className="rdo-workforce-manual-cancel"
            onClick={() => {
              setSomandoAMao(false);
              setNewCollaboratorName("");
            }}
          >
            Cancelar
          </button>
        </form>
      ) : (
        <button
          type="button"
          className="add-button rdo-workforce-manual-toggle"
          onClick={() => setSomandoAMao(true)}
        >
          Somar alguém à mão
        </button>
      )}
    </section>
  );
}
