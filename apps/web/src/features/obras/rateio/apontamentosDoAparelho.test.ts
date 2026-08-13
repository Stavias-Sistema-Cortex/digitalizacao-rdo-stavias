import { describe, expect, it } from "vitest";

import type { LocalRdoRecord } from "../../../lib/db/db.types";

import { extrairApontamentos } from "./apontamentosDoAparelho";

function rdo(parcial: Partial<LocalRdoRecord>): LocalRdoRecord {
  return {
    id: "rdo-1",
    obraId: "obra-norte",
    programacaoId: null,
    numeroRdo: "RDO-0001",
    dataRdo: "2026-07-10",
    statusRdo: "ENVIADO",
    syncStatus: "SYNCED",
    versaoEntidade: 1,
    payload: {},
    createdAt: "2026-07-10T10:00:00.000Z",
    updatedAt: "2026-07-10T10:00:00.000Z",
    ...parcial,
  };
}

const JULHO = { inicio: "2026-07-01", fim: "2026-07-31" };

describe("apontamentos lidos dos RDOs do aparelho", () => {
  it("lê a mão de obra do RDO que veio do servidor", () => {
    const leitura = extrairApontamentos(
      [
        rdo({
          payload: {
            encarregadoObra: "FRENTE A",
            maoObra: [
              {
                colaboradorId: "col-1",
                nomeColaborador: "PESSOA UM",
                cargo: "MOTORISTA",
              },
            ],
          },
        }),
      ],
      JULHO,
    );

    expect(leitura.rdosLidos).toBe(1);
    expect(leitura.rdosSemConteudo).toBe(0);
    expect(leitura.apontamentos).toEqual([
      {
        colaboradorId: "col-1",
        nome: "PESSOA UM",
        funcao: "MOTORISTA",
        obraId: "obra-norte",
        obraNome: "",
        data: "2026-07-10",
        encarregado: "FRENTE A",
        rdoId: "rdo-1",
        numeroRdo: "RDO-0001",
      },
    ]);
  });

  /*
   * A reconciliação grava o cabeçalho assim que descobre o RDO e busca o
   * conteúdo depois, quarenta por passagem. Enquanto o conteúdo não chega, o
   * documento não tem mão de obra nenhuma — e chamar isso de "ninguém
   * trabalhou" encolheria a fatia da obra sem que nada tivesse mudado.
   */
  it("separa o cabeçalho sem conteúdo do RDO sem ninguém apontado", () => {
    const leitura = extrairApontamentos(
      [
        rdo({ id: "so-cabecalho", payload: { numeroRdo: "RDO-0002" } }),
        rdo({ id: "vazio-de-verdade", payload: { maoObra: [] } }),
      ],
      JULHO,
    );

    expect(leitura.rdosSemConteudo).toBe(1);
    expect(leitura.rdosLidos).toBe(1);
    expect(leitura.rdosSemMaoDeObra).toBe(1);
    expect(leitura.apontamentos).toHaveLength(0);
  });

  it("no rascunho local, só quem está marcado conta o dia", () => {
    const leitura = extrairApontamentos(
      [
        rdo({
          statusRdo: "RASCUNHO",
          syncStatus: "PENDING",
          payload: {
            maoObra: [
              {
                localId: "a",
                colaboradorId: "col-1",
                nomeColaborador: "QUEM VEIO",
                cargo: "AJUDANTE",
                selected: true,
              },
              {
                localId: "b",
                colaboradorId: "col-2",
                nomeColaborador: "QUEM FALTOU",
                cargo: "AJUDANTE",
                selected: false,
              },
            ],
          },
        }),
      ],
      JULHO,
    );

    expect(leitura.apontamentos.map((item) => item.nome)).toEqual([
      "QUEM VEIO",
    ]);
  });

  it("conta o rascunho: o RDO do dia passa horas assim antes de ser enviado", () => {
    const leitura = extrairApontamentos(
      [
        rdo({
          statusRdo: "RASCUNHO",
          payload: {
            maoObra: [{ colaboradorId: "col-1", nomeColaborador: "ANA" }],
          },
        }),
      ],
      JULHO,
    );

    expect(leitura.apontamentos).toHaveLength(1);
  });

  it("descarta o RDO apagado, pelo carimbo ou pelo status", () => {
    const leitura = extrairApontamentos(
      [
        rdo({
          id: "carimbado",
          canceladoEm: "2026-07-11T08:00:00.000Z",
          payload: {
            maoObra: [{ colaboradorId: "col-1", nomeColaborador: "ANA" }],
          },
        }),
        rdo({
          id: "status",
          statusRdo: "CANCELADA",
          payload: {
            maoObra: [{ colaboradorId: "col-2", nomeColaborador: "BRUNO" }],
          },
        }),
      ],
      JULHO,
    );

    expect(leitura.apontamentos).toHaveLength(0);
    expect(leitura.rdosLidos).toBe(0);
    expect(leitura.rdosSemConteudo).toBe(0);
  });

  it("respeita o período pedido", () => {
    const registros = [
      rdo({ id: "antes", dataRdo: "2026-06-30" }),
      rdo({ id: "dentro", dataRdo: "2026-07-01" }),
      rdo({ id: "depois", dataRdo: "2026-08-01" }),
    ].map((registro) => ({
      ...registro,
      payload: {
        maoObra: [{ colaboradorId: "col-1", nomeColaborador: "ANA" }],
      },
    }));

    const leitura = extrairApontamentos(registros, JULHO);

    expect(leitura.apontamentos.map((item) => item.rdoId)).toEqual(["dentro"]);
  });

  it("filtra pelas obras pedidas, e sem lista pega todas", () => {
    const registros = [
      rdo({ id: "a", obraId: "obra-norte" }),
      rdo({ id: "b", obraId: "obra-sul" }),
    ].map((registro) => ({
      ...registro,
      payload: {
        maoObra: [{ colaboradorId: "col-1", nomeColaborador: "ANA" }],
      },
    }));

    expect(
      extrairApontamentos(registros, { ...JULHO, obraIds: ["obra-norte"] })
        .apontamentos,
    ).toHaveLength(1);
    expect(
      extrairApontamentos(registros, { ...JULHO, obraIds: [] }).apontamentos,
    ).toHaveLength(2);
    expect(extrairApontamentos(registros, JULHO).apontamentos).toHaveLength(2);
  });

  /*
   * O campo de encarregado existe no RDO, mas ninguém o digita em campo — ele
   * só chega preenchido pela importação e pela clonagem. As outras duas portas
   * existem para a coluna não nascer vazia no RDO feito no aplicativo.
   */
  describe("de onde sai o encarregado da frente", () => {
    it("usa o campo declarado quando alguém o escreveu", () => {
      const [apontamento] = extrairApontamentos(
        [
          rdo({
            payload: {
              encarregadoObra: "FRENTE B",
              apontadorRdo: "ELIAS",
              maoObra: [
                {
                  colaboradorId: "col-9",
                  nomeColaborador: "JOAQUIM",
                  cargo: "ENCARREGADO DE OBRA",
                },
              ],
            },
          }),
        ],
        JULHO,
      ).apontamentos;

      expect(apontamento.encarregado).toBe("FRENTE B");
    });

    it("sem campo, acha quem tem cargo de encarregado na mão de obra", () => {
      const [apontamento] = extrairApontamentos(
        [
          rdo({
            payload: {
              apontadorRdo: "ELIAS",
              maoObra: [
                {
                  colaboradorId: "col-1",
                  nomeColaborador: "ANTONIO",
                  cargo: "AJUDANTE DE OBRA",
                },
                {
                  colaboradorId: "col-9",
                  nomeColaborador: "CHEFE DA FRENTE",
                  cargo: "ENCARREGADO DE OBRA",
                },
              ],
            },
          }),
        ],
        JULHO,
      ).apontamentos;

      expect(apontamento.encarregado).toBe("CHEFE DA FRENTE");
    });

    it("acha o encarregado mesmo com o cargo escrito sem acento", () => {
      const [apontamento] = extrairApontamentos(
        [
          rdo({
            payload: {
              maoObra: [
                {
                  colaboradorId: "col-9",
                  nomeColaborador: "JOÃO DA PONTE",
                  cargo: "encarregado geral de obras",
                },
              ],
            },
          }),
        ],
        JULHO,
      ).apontamentos;

      expect(apontamento.encarregado).toBe("JOÃO DA PONTE");
    });

    it("sem nenhuma das duas, assina o apontador do RDO", () => {
      const [apontamento] = extrairApontamentos(
        [
          rdo({
            payload: {
              apontadorRdo: "QUEM ASSINA",
              maoObra: [
                {
                  colaboradorId: "col-1",
                  nomeColaborador: "ANTONIO",
                  cargo: "AJUDANTE DE OBRA",
                },
              ],
            },
          }),
        ],
        JULHO,
      ).apontamentos;

      expect(apontamento.encarregado).toBe("QUEM ASSINA");
    });
  });

  it("aceita a pessoa somada à mão, que não tem cadastro", () => {
    const [apontamento] = extrairApontamentos(
      [
        rdo({
          payload: {
            maoObra: [{ colaboradorId: null, nomeColaborador: "AJUDANTE NOVO" }],
          },
        }),
      ],
      JULHO,
    ).apontamentos;

    expect(apontamento.colaboradorId).toBeNull();
    expect(apontamento.nome).toBe("AJUDANTE NOVO");
  });

  it("ignora linha sem cadastro e sem nome, e lixo que não é objeto", () => {
    const leitura = extrairApontamentos(
      [
        rdo({
          payload: {
            maoObra: [
              { colaboradorId: "", nomeColaborador: "   " },
              null,
              "texto solto",
              { colaboradorId: "col-1", nomeColaborador: "ANA" },
            ],
          },
        }),
      ],
      JULHO,
    );

    expect(leitura.apontamentos).toHaveLength(1);
    expect(leitura.apontamentos[0].nome).toBe("ANA");
  });

  /*
   * Quem assina o RDO esteve lá.
   *
   * <p>O rateio lia só a lista de mão de obra, e quem preenche o documento
   * quase nunca se inclui nela — a lista é da equipe. O resultado era um RDO
   * de um dia inteiro de trabalho entrando no rateio como dia de ninguém: o
   * gestor procurava pelo nome de quem assinou o dia e não achava nada.
   */
  describe("quem assina o RDO conta como presente", () => {
    it("conta quem preencheu, mesmo sem ninguém apontado na mão de obra", () => {
      const leitura = extrairApontamentos(
        [rdo({ payload: { preenchidoPor: "PESSOA QUE ASSINA", maoObra: [] } })],
        JULHO,
      );

      expect(leitura.apontamentos).toHaveLength(1);
      expect(leitura.apontamentos[0]).toMatchObject({
        nome: "PESSOA QUE ASSINA",
        funcao: "Preencheu o RDO",
        obraId: "obra-norte",
        data: "2026-07-10",
        colaboradorId: null,
      });
    });

    /*
     * A função é da pessoa, não do papel: quando o cadastro do Academy diz o
     * ofício do apontador, é ele que aparece na coluna — "Apontador do RDO"
     * fica para quem assinou sem cadastro por trás.
     */
    it("usa a função do Academy quando o servidor a conhece", () => {
      const [apontamento] = extrairApontamentos(
        [
          rdo({
            payload: {
              apontadorRdo: "QUEM APONTA",
              apontadorColaboradorId: "col-77",
              apontadorFuncao: "APONTADOR DE OBRA",
              maoObra: [],
            },
          }),
        ],
        JULHO,
      ).apontamentos;

      expect(apontamento.funcao).toBe("APONTADOR DE OBRA");
    });

    it("o apontador escolhido da lista entra com o cadastro dele", () => {
      const [apontamento] = extrairApontamentos(
        [
          rdo({
            payload: {
              apontadorRdo: "QUEM APONTA",
              apontadorColaboradorId: "col-77",
              maoObra: [],
            },
          }),
        ],
        JULHO,
      ).apontamentos;

      expect(apontamento).toMatchObject({
        colaboradorId: "col-77",
        nome: "QUEM APONTA",
        funcao: "Apontador do RDO",
      });
    });

    /* Quem já está na equipe vale pela equipe, com o cargo que declarou. */
    it("não duplica quem já está apontado na mão de obra", () => {
      const leitura = extrairApontamentos(
        [
          rdo({
            payload: {
              apontadorRdo: "MARIA",
              preenchidoPor: "MARIA",
              maoObra: [
                { colaboradorId: "col-3", nomeColaborador: "MARÍA", cargo: "TOPÓGRAFA" },
              ],
            },
          }),
        ],
        JULHO,
      );

      expect(leitura.apontamentos).toHaveLength(1);
      expect(leitura.apontamentos[0].funcao).toBe("TOPÓGRAFA");
    });

    /* Assinar duas vezes o mesmo dia não faz de ninguém duas pessoas. */
    it("quem apontou e preencheu entra uma vez só", () => {
      const leitura = extrairApontamentos(
        [
          rdo({
            payload: {
              apontadorRdo: "ELIAS",
              preenchidoPor: "ELIAS",
              maoObra: [],
            },
          }),
        ],
        JULHO,
      );

      expect(leitura.apontamentos).toHaveLength(1);
      expect(leitura.apontamentos[0].funcao).toBe("Apontador do RDO");
    });

    /*
     * O campo de encarregado é texto livre e recebe tanto gente quanto o nome
     * da frente. Virar pessoa criaria um colaborador que não existe, comendo
     * fatia de obra como se fosse gente.
     */
    it("não inventa uma pessoa a partir do encarregado, que pode ser a frente", () => {
      const leitura = extrairApontamentos(
        [rdo({ payload: { encarregadoObra: "FRENTE A", maoObra: [] } })],
        JULHO,
      );

      expect(leitura.apontamentos).toHaveLength(0);
      expect(leitura.rdosSemMaoDeObra).toBe(1);
    });

    /* Cabeçalho sem conteúdo continua sendo "não sei", não "só o que assina". */
    it("o RDO que ainda não foi aberto não vira apontamento de assinatura", () => {
      const leitura = extrairApontamentos(
        [rdo({ payload: { preenchidoPor: "PESSOA QUE ASSINA" } })],
        JULHO,
      );

      expect(leitura.apontamentos).toHaveLength(0);
      expect(leitura.rdosSemConteudo).toBe(1);
    });
  });
});
