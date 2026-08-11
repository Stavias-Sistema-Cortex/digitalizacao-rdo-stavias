// @vitest-environment jsdom

import { useState } from "react";

import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "./createEmptyRdo";
import { carryForwardWorkforce } from "./rdoWorkforceCarryForward";
import { RdoWorkforceEditor } from "./RdoWorkforceEditor";
import type {
  RdoContextCollaborator,
  RdoPreviousWorkforceItem,
} from "./rdoLookupApi";

const CATALOGO: RdoContextCollaborator[] = [
  {
    id: "worker-a",
    codigoColaborador: "001",
    nome: "Ana",
    papelNaObra: "APONTADOR",
    nomePerfil: "Apontadora",
  },
  {
    id: "worker-b",
    codigoColaborador: "002",
    nome: "Bruno",
    papelNaObra: "OPERACIONAL",
    nomePerfil: "Operador",
  },
  {
    id: "worker-c",
    codigoColaborador: "003",
    nome: "Carla",
    papelNaObra: "OPERACIONAL",
    nomePerfil: "Sinaleira",
  },
];

/** A equipe que o RDO-0001 fechou ontem. */
function equipeDoRdoAnterior(): RdoPreviousWorkforceItem[] {
  return [
    {
      sourceRdoId: "rdo-0001",
      sourceItemId: "item-ana",
      collaboratorId: "worker-a",
      nameSnapshot: "Ana",
      roleSnapshot: "Apontadora",
      linkType: "PROPRIO",
      quantity: 1,
      startTime: "07:00:00",
      endTime: "17:00:00",
      observations: "",
      availability: "AVAILABLE",
    },
    {
      sourceRdoId: "rdo-0001",
      sourceItemId: "item-bruno",
      collaboratorId: "worker-b",
      nameSnapshot: "Bruno",
      roleSnapshot: "Operador",
      linkType: "PROPRIO",
      quantity: 1,
      startTime: "07:00:00",
      endTime: "17:00:00",
      observations: "",
      availability: "AVAILABLE",
    },
  ];
}

/**
 * O editor é controlado: quem guarda o rascunho é a tela que o usa. Um mock de
 * onChange sem estado devolveria sempre o mesmo draft, e cada tecla digitada
 * cairia sobre o valor original — o que mediria o teste, não o app. Este pai
 * mínimo faz o que a tela real faz.
 */
function EditorComEstado(props: {
  inicial: ReturnType<typeof createEmptyRdo>;
  aoMudar?: (draft: ReturnType<typeof createEmptyRdo>) => void;
}) {
  const [draft, setDraft] = useState(props.inicial);
  return (
    <RdoWorkforceEditor
      draft={draft}
      collaborators={CATALOGO}
      sourceRdoNumber="RDO-0001"
      onChange={(proximo) => {
        setDraft(proximo);
        props.aoMudar?.(proximo);
      }}
    />
  );
}

afterEach(cleanup);

/**
 * A equipe de ontem é o ponto de partida de hoje, não uma decisão fechada.
 *
 * Quem aponta em campo raramente troca a turma inteira de um dia para o outro:
 * o RDO seguinte começa com a mesma gente, e o trabalho da pessoa é ajustar as
 * exceções — quem faltou, quem chegou mais tarde, quem entrou hoje. Se a
 * herança viesse travada, o ganho viraria estorvo; se não viesse, cada dia
 * começaria do zero. As três coisas precisam valer juntas, e é a costura entre
 * elas que este teste guarda: cada peça já tinha teste, a jornada não tinha.
 */
describe("equipe do RDO seguinte", () => {
  it("herda a turma do RDO anterior já marcada para incluir", () => {
    const herdada = carryForwardWorkforce(
      equipeDoRdoAnterior(),
      CATALOGO,
      (() => {
        let n = 0;
        return () => `linha-${(n += 1)}`;
      })(),
    );

    expect(herdada).toHaveLength(2);
    expect(herdada.map((linha) => linha.nomeColaborador)).toEqual([
      "Ana",
      "Bruno",
    ]);
    // Vir marcada é o que faz a herança poupar trabalho em vez de criá-lo.
    expect(herdada.every((linha) => linha.selected)).toBe(true);
    expect(herdada.every((linha) => linha.origin === "PREVIOUS_RDO")).toBe(true);
    // A proveniência aponta para o RDO de origem: o dado de hoje sabe de onde
    // veio, que é o que permite auditar a jornada depois.
    expect(herdada.every((linha) => linha.sourceRdoId === "rdo-0001")).toBe(
      true,
    );
    expect(herdada[0].horaInicio).toBe("07:00");
    expect(herdada[0].horaFim).toBe("17:00");
  });

  /*
   * O horário por pessoa saiu da tela: ele repetia, doze vezes, o turno que a
   * Identificação já declara, e ninguém o ajustava. O que veio do RDO anterior
   * continua no rascunho e continua subindo — o que deixou de existir é a
   * coluna que pedia para redigitá-lo.
   */
  it("carrega o horário herdado sem pedir que alguém o redigite", () => {
    const herdada = carryForwardWorkforce(equipeDoRdoAnterior(), CATALOGO);

    render(
      <EditorComEstado
        inicial={{
          ...createEmptyRdo(),
          previousRdoId: "rdo-0001",
          maoObra: herdada,
        }}
        aoMudar={vi.fn()}
      />,
    );

    expect(screen.queryByLabelText("Início de Ana")).toBeNull();
    expect(herdada[0]).toMatchObject({
      horaInicio: "07:00",
      horaFim: "17:00",
    });
  });

  /** A turma de hoje pode ganhar gente que ontem não estava. */
  it("deixa somar ao herdado alguém do catálogo da obra", async () => {
    const user = userEvent.setup();
    const aoMudar = vi.fn();

    render(
      <EditorComEstado
        inicial={{
          ...createEmptyRdo(),
          previousRdoId: "rdo-0001",
          maoObra: carryForwardWorkforce(equipeDoRdoAnterior(), CATALOGO),
        }}
        aoMudar={aoMudar}
      />,
    );

    const carla = screen
      .getAllByRole("listitem")
      .find((linha) => /Carla/.test(linha.textContent ?? ""));
    await user.click(within(carla!).getByRole("checkbox"));

    const ultimo = aoMudar.mock.calls.at(-1)?.[0];
    expect(ultimo.maoObra).toHaveLength(3);
    // A herdada continua herdada; a nova entra como adição deste RDO.
    expect(
      ultimo.maoObra.map(
        (linha: { nomeColaborador: string; origin: string }) =>
          `${linha.nomeColaborador}:${linha.origin}`,
      ),
    ).toEqual([
      "Ana:PREVIOUS_RDO",
      "Bruno:PREVIOUS_RDO",
      "Carla:AUTHORIZED_CONTEXT",
    ]);
  });

  /**
   * Quem saiu da obra entre um RDO e outro não atravessa.
   *
   * A linha travada e cinza reaparecia todo dia, e apagar a equipe deixava
   * seus membros escritos no RDO seguinte, e no seguinte, sem jeito de
   * tirá-los a não ser um a um. O RDO de ontem continua guardando quem
   * trabalhou ontem — o que para é a repetição para a frente.
   */
  it("não repete para a frente quem não está mais autorizado", () => {
    const anterior = equipeDoRdoAnterior();
    anterior.push({
      sourceRdoId: "rdo-0001",
      sourceItemId: "item-antigo",
      collaboratorId: "worker-desligado",
      nameSnapshot: "Diego",
      roleSnapshot: "Servente",
      linkType: "PROPRIO",
      quantity: 1,
      startTime: "07:00:00",
      endTime: "17:00:00",
      observations: "",
      availability: "AVAILABLE",
    });

    const herdada = carryForwardWorkforce(anterior, CATALOGO);

    expect(
      herdada.some((linha) => linha.colaboradorId === "worker-desligado"),
    ).toBe(false);
    // Quem continua autorizado atravessa normalmente: o corte é só de quem
    // perdeu a porta de entrada na obra.
    expect(herdada.map((linha) => linha.nomeColaborador)).toEqual([
      "Ana",
      "Bruno",
    ]);
  });
});
