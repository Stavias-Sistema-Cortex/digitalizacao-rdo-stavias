import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { FinanceRevenueTrace } from "./financeiroApi";
import { FinanceRevenueTraceContent } from "./FinanceRevenueTracePage";

const EMPTY_TRACE: FinanceRevenueTrace = {
  consolidado: {
    producao: 0,
    custo: 0,
    receitaEstimada: 0,
    margem: 0,
    receitaMedida: 0,
    receitaAprovada: 0,
    receitaFaturada: 0,
    receitaRecebida: 0,
  },
  obras: [],
  tiposServico: [],
};

const TRACE_WITHOUT_CONTRACT_VALUE: FinanceRevenueTrace = {
  consolidado: {
    producao: 20,
    custo: 250,
    receitaEstimada: 0,
    margem: -250,
    receitaMedida: 0,
    receitaAprovada: 0,
    receitaFaturada: 0,
    receitaRecebida: 0,
  },
  obras: [{
    id: "obra-1",
    nome: "Obra real",
    totais: {
      producao: 20,
      custo: 250,
      receitaEstimada: 0,
      margem: -250,
      receitaMedida: 0,
      receitaAprovada: 0,
      receitaFaturada: 0,
      receitaRecebida: 0,
    },
  }],
  tiposServico: [{
    nome: "Serviço sem preço contratual",
    unidade: "m",
    totais: {
      producao: 20,
      custo: 250,
      receitaEstimada: 0,
      margem: -250,
      receitaMedida: 0,
      receitaAprovada: 0,
      receitaFaturada: 0,
      receitaRecebida: 0,
    },
    obras: [{
      obraId: "obra-1",
      obraNome: "Obra real",
      itemContratualId: null,
      codigoItemContratual: null,
      totais: {
        producao: 20,
        custo: 250,
        receitaEstimada: 0,
        margem: -250,
        receitaMedida: 0,
        receitaAprovada: 0,
        receitaFaturada: 0,
        receitaRecebida: 0,
      },
      quantidadeRdos: 1,
      rdoIds: ["rdo-1"],
      receitaDisponivel: false,
    }],
    quantidadeRdos: 1,
    receitaDisponivel: false,
  }],
};

describe("FinanceRevenueTraceContent", () => {
  it("mostra uma ausência honesta quando não há execução validada para compor receita", () => {
    const markup = renderToStaticMarkup(
      <FinanceRevenueTraceContent
        data={EMPTY_TRACE}
        onSelectService={() => undefined}
      />,
    );

    expect(markup).toContain("Ainda não há receita operacional registrada");
    expect(markup).toContain("execução de serviço validada");
    expect(markup).not.toContain("Receita estimada");
    expect(markup).not.toContain("R$ 0,00");
  });

  it("não converte receita sem preço contratual em um zero fictício", () => {
    const markup = renderToStaticMarkup(
      <FinanceRevenueTraceContent
        data={TRACE_WITHOUT_CONTRACT_VALUE}
        onSelectService={() => undefined}
      />,
    );

    expect(markup).toContain("Receita indisponível");
    expect(markup).toContain("preço contratual");
    expect(markup).not.toContain("Receita estimada</span><strong>R$");
  });
});
