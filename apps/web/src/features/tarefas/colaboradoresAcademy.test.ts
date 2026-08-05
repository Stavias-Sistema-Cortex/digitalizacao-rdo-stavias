import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";

const api = vi.hoisted(() => ({
  buscarColaboradores: vi.fn(),
  buscarColaboradoresDaObra: vi.fn(),
  buscarColaboradoresParaEquipe: vi.fn(),
}));

vi.mock("../rdos/rdoLookupApi", () => api);

import { hidratarColaboradoresAcademy } from "./colaboradoresAcademy";

const COLABORADOR_ID = "00000000-0000-4000-8000-0000000008e1";
const OBRA_ID = "00000000-0000-4000-8000-0000000008e2";

let databaseName = "";

function pessoa(id: string, nome: string) {
  return {
    id,
    nome,
    cpfMascarado: "***.111.***-**",
    nomePerfil: "Encarregado",
    nomeGrupo: "OPERACIONAL",
    ativo: true,
    atualizadoEm: null,
  };
}

describe("de onde vem a lista de pessoas ao montar a equipe", () => {
  beforeEach(async () => {
    vi.stubGlobal("window", new EventTarget());
    setSession({
      colaboradorId: COLABORADOR_ID,
      nome: "Beta",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: "2099-01-01T00:00:00.000Z",
    });
    databaseName = await databaseNameForScope(
      COLABORADOR_ID,
      `BETA:${OBRA_ID}`,
    );
    api.buscarColaboradores.mockResolvedValue([]);
    api.buscarColaboradoresDaObra.mockResolvedValue([
      pessoa("ja-na-obra", "CARLOS ALBERTO"),
    ]);
    api.buscarColaboradoresParaEquipe.mockResolvedValue([
      pessoa("de-fora", "ADAO LEITE"),
    ]);
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
    vi.clearAllMocks();
  });

  /*
   * A tela precisa abrir povoada com quem já pertence à obra — sem isso, quem
   * só quer conferir a frente atual teria de digitar para ver qualquer coisa.
   */
  it("sem termo, lista quem já pertence à obra", async () => {
    const pessoas = await hidratarColaboradoresAcademy("", OBRA_ID);

    expect(api.buscarColaboradoresDaObra).toHaveBeenCalledWith(OBRA_ID);
    expect(api.buscarColaboradoresParaEquipe).not.toHaveBeenCalled();
    expect(pessoas.map((p) => p.id)).toEqual(["ja-na-obra"]);
  });

  /*
   * O destravamento: as duas pontas se prendiam porque, para entrar na equipe
   * da obra, era preciso já pertencer à obra — e a equipe é justamente a porta
   * de entrada. Com termo, a busca alcança quem ainda está de fora.
   */
  it("com termo, alcança quem ainda não pertence à obra", async () => {
    const pessoas = await hidratarColaboradoresAcademy("adao", OBRA_ID);

    expect(api.buscarColaboradoresParaEquipe)
      .toHaveBeenCalledWith(OBRA_ID, "adao");
    expect(api.buscarColaboradoresDaObra).not.toHaveBeenCalled();
    expect(pessoas.map((p) => p.id)).toEqual(["de-fora"]);
  });

  /*
   * Espaço em branco não é termo. Tratá-lo como busca faria a tela pedir ao
   * servidor uma varredura do cadastro a cada tecla apagada.
   */
  it("não trata espaço em branco como termo de busca", async () => {
    await hidratarColaboradoresAcademy("   ", OBRA_ID);

    expect(api.buscarColaboradoresDaObra).toHaveBeenCalledWith(OBRA_ID);
    expect(api.buscarColaboradoresParaEquipe).not.toHaveBeenCalled();
  });

  /*
   * Sem obra, o caminho é o catálogo global — a leitura administrativa que o
   * Alfa usa para formar equipe numa obra onde ainda não há ninguém.
   */
  it("sem obra, continua no catálogo global", async () => {
    await hidratarColaboradoresAcademy("adao");

    expect(api.buscarColaboradores).toHaveBeenCalledWith("adao");
    expect(api.buscarColaboradoresParaEquipe).not.toHaveBeenCalled();
    expect(api.buscarColaboradoresDaObra).not.toHaveBeenCalled();
  });
});
