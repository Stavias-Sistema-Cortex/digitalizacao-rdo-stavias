import { describe, expect, it } from "vitest";

import { createEmptyRdo } from "./createEmptyRdo";
import type { RdoDraft } from "./rdo.types";
import {
  rascunhoClonadoDe,
  resumoDoQueOCloneTraz,
} from "./rascunhoClonado";

function idsSequenciais(): () => string {
  let n = 0;
  return () => `novo-${++n}`;
}

function rdoDeOntem(): RdoDraft {
  const draft = createEmptyRdo();
  return {
    ...draft,
    id: "rdo-de-ontem",
    obraId: "obra-1",
    dataRdo: "2026-08-04",
    numeroRdo: "RDO-0041",
    previousRdoId: "rdo-0040",
    previousRdoNumber: "RDO-0040",
    creationContextVersion: 11,
    programacaoId: "prog-1",
    cliente: "Cliente",
    turno: "DIURNO",
    horaInicio: "07:00",
    horaFim: "17:00",
    kmInicialProgramado: "400",
    kmFinalProgramado: "398",
    encarregadoObra: "Carlos",
    fiscalizacaoCampo: "Fiscal",
    preenchidoPor: "Apontador",
    condicaoManha: "BOM",
    condicaoTarde: "CHUVA",
    condicaoTrabalho: "PRATICAVEL",
    pluviometriaMm: 12,
    observacoes: "Choveu à tarde.",
    servicosExecutados: [
      {
        ...draft.servicosExecutados[0],
        localId: "s1",
        serviceId: "srv-1",
        servicoNome: "Fresagem",
        quantidadeExecutada: 1200,
        trechoInicial: "400",
        trechoFinal: "398",
        larguraM: 7,
        espessuraCm: 5,
      },
    ] as RdoDraft["servicosExecutados"],
    equipamentos: [
      {
        localId: "e1",
        assetId: "asset-1",
        prefixo: "RE-01",
        descricao: "Retroescavadeira",
        tipoEquipamento: "ESCAVACAO",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
        horaInicio: "07:00",
        horaFim: "17:00",
        observacoes: "Sem intercorrência.",
      },
    ],
    materiais: [
      {
        localId: "m1",
        materialNome: "CBUQ",
        unidade: "t",
        quantidadePrevista: 100,
        quantidadeUsinada: 98,
        quantidadeAplicada: 95,
        quantidadeSobra: 3,
        notaFiscal: "NF-123",
        fornecedor: "Usina",
        observacoes: "",
      },
    ],
    maoObra: [
      {
        ...draft.maoObra[0],
        localId: "mo1",
        colaboradorId: "col-1",
        nomeColaborador: "Adão",
        selected: true,
      },
    ] as RdoDraft["maoObra"],
    attachments: [
      { id: "foto-1" },
    ] as unknown as RdoDraft["attachments"],
  };
}

describe("clonar um RDO", () => {
  /*
   * A razão de existir da clonagem: o que se repete de um dia para o outro não
   * deve ser redigitado.
   */
  it("traz o turno, o trecho programado e quem assina", () => {
    const clone = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais());

    expect(clone.turno).toBe("DIURNO");
    expect(clone.horaInicio).toBe("07:00");
    expect(clone.kmInicialProgramado).toBe("400");
    expect(clone.kmFinalProgramado).toBe("398");
    expect(clone.encarregadoObra).toBe("Carlos");
    expect(clone.fiscalizacaoCampo).toBe("Fiscal");
  });

  /*
   * A linha inteira nascia vazia, e refazê-la custava escolher o serviço no
   * catálogo, o preço, a unidade, a pista, a faixa e o quilômetro — tudo igual
   * ao do dia anterior. Era o maior trabalho de uma clonagem.
   */
  it("traz a frente de serviço com a identidade preenchida", () => {
    const clone = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais());

    expect(clone.servicosExecutados).toHaveLength(1);
    expect(clone.servicosExecutados[0]).toMatchObject({
      serviceId: "srv-1",
      servicoNome: "Fresagem",
      trechoInicial: "400",
      trechoFinal: "398",
    });
  });

  /*
   * E deixa em branco só os números: produção herdada vira produção
   * confirmada sem conferência, que é o erro que a clonagem existe para
   * evitar.
   */
  it("deixa em branco os números do dia no serviço copiado", () => {
    const [servico] = rascunhoClonadoDe(
      rdoDeOntem(),
      idsSequenciais(),
    ).servicosExecutados;

    expect(servico.quantidadeExecutada).toBe("");
    expect(servico.larguraM).toBe("");
    expect(servico.espessuraCm).toBe("");
  });

  /*
   * Validar é ato de outra pessoa sobre um dia específico. Herdar a validação
   * de ontem assinaria por ela — e, com preço, viraria receita medida.
   */
  it("devolve o serviço copiado ao estado de registrado", () => {
    const [servico] = rascunhoClonadoDe(
      rdoDeOntem(),
      idsSequenciais(),
    ).servicosExecutados;

    expect(servico.statusValidacao).toBe("REGISTRADA");
  });

  it("não traz clima, pluviometria, observação nem condição do dia", () => {
    const clone = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais());

    expect(clone.condicaoManha).toBe("");
    expect(clone.condicaoTarde).toBe("");
    expect(clone.condicaoNoite).toBe("");
    expect(clone.condicaoTrabalho).toBe("");
    expect(clone.pluviometriaMm).toBe("");
    expect(clone.observacoes).toBe("");
  });

  it("não traz as fotos, que são do dia que passou", () => {
    expect(rascunhoClonadoDe(rdoDeOntem(), idsSequenciais()).attachments)
      .toEqual([]);
  });

  it("traz o equipamento, sem a jornada dele", () => {
    const [equipamento] = rascunhoClonadoDe(
      rdoDeOntem(),
      idsSequenciais(),
    ).equipamentos;

    expect(equipamento.prefixo).toBe("RE-01");
    expect(equipamento.assetId).toBe("asset-1");
    expect(equipamento.quantidade).toBe("");
    expect(equipamento.horaInicio).toBe("");
    expect(equipamento.horaFim).toBe("");
    expect(equipamento.observacoes).toBe("");
  });

  it("traz o material, sem quantidade nem nota fiscal", () => {
    const [material] = rascunhoClonadoDe(
      rdoDeOntem(),
      idsSequenciais(),
    ).materiais;

    expect(material.materialNome).toBe("CBUQ");
    expect(material.unidade).toBe("t");
    expect(material.fornecedor).toBe("Usina");
    expect(material.quantidadeUsinada).toBe("");
    expect(material.quantidadeAplicada).toBe("");
    expect(material.quantidadeSobra).toBe("");
    expect(material.notaFiscal).toBe("");
  });

  /*
   * Herdar o número de outro RDO é o desfecho mais caro de todos, porque
   * sobrevive à gravação e contamina o relatório. O contexto sobrescreve estes
   * campos, mas zerá-los aqui garante que uma falha do contexto não os deixe
   * passar.
   */
  it("não herda número, RDO anterior nem versão do contexto", () => {
    const clone = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais());

    expect(clone.numeroRdo).toBe("");
    expect(clone.previousRdoId).toBe("");
    expect(clone.previousRdoNumber).toBe("");
    expect(clone.creationContextVersion).toBeNull();
    expect(clone.programacaoId).toBe("");
  });

  /*
   * O recurso se chama "clonar para outra data". Herdar a data faria o diálogo
   * pré-selecionar o dia do qual se copia — e como um segundo RDO no mesmo dia
   * passou a ser aceito, e não há restrição de unicidade no banco, o clone
   * distraído viraria duplicata sem que nada reclamasse.
   */
  it("não herda a data da origem", () => {
    expect(rascunhoClonadoDe(rdoDeOntem(), idsSequenciais()).dataRdo).toBe("");
  });

  /*
   * Quem preenche é quem está com a sessão aberta. comPreenchidoPor só preenche
   * campo vazio, então herdar aqui faria o RDO alegar autoria de outra pessoa.
   */
  it("não herda quem preencheu o RDO copiado", () => {
    expect(
      rascunhoClonadoDe(rdoDeOntem(), idsSequenciais()).preenchidoPor,
    ).toBe("");
  });

  it("nasce com identidade própria e como rascunho local", () => {
    const clone = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais());

    expect(clone.id).not.toBe("rdo-de-ontem");
    expect(clone.syncStatus).toBe("LOCAL_ONLY");
  });

  /*
   * O pedido mais direto de quem usa: clonar tem que trazer as mesmas pessoas
   * já colocadas da equipe. Elas vinham zeradas e eram reconstruídas do RDO
   * ANTERIOR — que numa clonagem quase nunca é o RDO escolhido para copiar.
   * Quem clonava o de segunda para repetir a frente recebia a equipe de sexta.
   */
  it("traz as mesmas pessoas da equipe, já selecionadas", () => {
    const clone = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais());

    expect(clone.maoObra).toHaveLength(1);
    expect(clone.maoObra[0]).toMatchObject({
      colaboradorId: "col-1",
      nomeColaborador: "Adão",
      selected: true,
    });
  });

  /*
   * A identidade dela repete; o dia dela, não.
   */
  it("deixa em branco a jornada da pessoa copiada", () => {
    const [pessoa] = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais()).maoObra;

    expect(pessoa.horaInicio).toBe("");
    expect(pessoa.horaFim).toBe("");
    expect(pessoa.percentualDia).toBe("");
  });

  /*
   * `origemItemId` apontaria para a linha de outro documento, e o servidor a
   * lê como herança declarada. Dizer que veio de onde não veio quebraria a
   * cadeia.
   */
  it("não alega que a pessoa foi herdada do RDO anterior", () => {
    const [pessoa] = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais()).maoObra;

    expect(pessoa.origemItemId).toBe("");
    expect(pessoa.sourceRdoId).toBe("");
    expect(pessoa.origin).toBe("MANUAL");
  });

  it("não se apresenta como documento importado", () => {
    expect(rascunhoClonadoDe(rdoDeOntem(), idsSequenciais()).importEvidence)
      .toBeNull();
  });

  it("dá identidade local nova a cada linha copiada", () => {
    const clone = rascunhoClonadoDe(rdoDeOntem(), idsSequenciais());

    expect(clone.equipamentos[0].localId).not.toBe("e1");
    expect(clone.materiais[0].localId).not.toBe("m1");
    expect(clone.equipamentos[0].localId)
      .not.toBe(clone.materiais[0].localId);
  });

  it("não altera o RDO de origem", () => {
    const origem = rdoDeOntem();
    rascunhoClonadoDe(origem, idsSequenciais());

    expect(origem.servicosExecutados).toHaveLength(1);
    expect(origem.equipamentos[0].horaInicio).toBe("07:00");
    expect(origem.id).toBe("rdo-de-ontem");
  });
});

describe("resumo do que o clone traz", () => {
  it("diz o que copia e o que deixa em branco", () => {
    const resumo = resumoDoQueOCloneTraz(rdoDeOntem());

    expect(resumo).toContain("turno e horário");
    expect(resumo).toContain("trecho programado");
    expect(resumo).toContain("1 equipamento");
    expect(resumo).toContain("1 material");
    expect(resumo).toContain("1 frente de serviço");
    expect(resumo).toContain(
      "As quantidades, as medidas, as horas e o clima ficam em branco",
    );
  });

  it("avisa quando não há nada que valha copiar", () => {
    expect(resumoDoQueOCloneTraz(createEmptyRdo()))
      .toBe("Este RDO não tem nada que valha copiar.");
  });

  it("pluraliza pela contagem real", () => {
    const origem = rdoDeOntem();
    const resumo = resumoDoQueOCloneTraz({
      ...origem,
      equipamentos: [origem.equipamentos[0], origem.equipamentos[0]],
    });

    expect(resumo).toContain("2 equipamentos");
  });
});
