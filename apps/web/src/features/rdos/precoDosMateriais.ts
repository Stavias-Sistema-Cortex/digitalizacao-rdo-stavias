import type { LocalServiceCatalogRow } from "../financeiro/servicePriceRepository";
import { normalizarUnidade } from "../financeiro/servicoCatalogoEntrada";
import { dataHojeEmBrasilia } from "../../lib/tempo/fusoBrasilia";
import { calcularSobraMaterial } from "./rdoCalculations";
import type { MaterialDraft, NumericInput } from "./rdo.types";

/**
 * Valor dos materiais do RDO ao preço do catálogo local da obra.
 *
 * A mesma régua do painel de usinados, aplicada ao que este aparelho já tem:
 * o preço vale quando existe exatamente um vigente, em BRL, com o mesmo nome
 * e a mesma unidade do material — zero ou mais de um deixa o material fora da
 * conta, contado em `materiaisSemPreco` para a tela dizer o porquê. Nada vai
 * à rede: o catálogo local é o que a sincronização trouxe, e sem ele (ou sem
 * permissão financeira) simplesmente não há preço para casar.
 */
export interface ValorDosMateriais {
  /** Nulo quando nenhum material com preço declarou quantidade aplicada. */
  valorAplicado: number | null;
  /** Nulo quando nenhum material com preço tem sobra positiva calculável. */
  valorSobra: number | null;
  materiaisComPreco: number;
  /** Materiais preenchidos cujo nome+unidade não casou um preço único. */
  materiaisSemPreco: number;
}

function asNumber(value: NumericInput): number | null {
  return typeof value === "number" && Number.isFinite(value)
    ? value
    : null;
}

function nomeNormalizado(value: string): string {
  return value.trim().toLocaleLowerCase("pt-BR");
}

function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

/**
 * O preço único vigente para um nome+unidade, ou nulo.
 *
 * Espelha a seleção do servidor ({@code PRECOS_VIGENTES_DO_NOME}): serviço
 * ativo com o mesmo nome, versão ativa em BRL na mesma unidade, vigente na
 * data de referência pela vigência efetiva. Mais de um candidato é ambíguo, e
 * ambíguo não valora.
 */
export function precoLocalDoMaterial(
  material: Pick<MaterialDraft, "materialNome" | "unidade">,
  catalogo: readonly LocalServiceCatalogRow[],
  hoje: string = dataHojeEmBrasilia(),
): number | null {
  const nome = nomeNormalizado(material.materialNome);
  const unidade = normalizarUnidade(material.unidade);
  if (!nome || !unidade) {
    return null;
  }

  const candidatos: number[] = [];
  for (const linha of catalogo) {
    if (
      linha.service.status !== "ACTIVE" ||
      nomeNormalizado(linha.service.name) !== nome
    ) {
      continue;
    }
    for (const preco of linha.priceVersions) {
      if (
        preco.status !== "ACTIVE" ||
        preco.currency !== "BRL" ||
        normalizarUnidade(preco.unit) !== unidade ||
        preco.validFrom.slice(0, 10) > hoje ||
        (preco.effectiveValidTo !== null &&
          preco.effectiveValidTo.slice(0, 10) < hoje)
      ) {
        continue;
      }
      const valor = Number(preco.unitPrice);
      if (Number.isFinite(valor) && valor >= 0) {
        candidatos.push(valor);
      }
    }
  }

  return candidatos.length === 1 ? candidatos[0] : null;
}

export function valorLocalDosMateriais(
  materiais: readonly MaterialDraft[],
  catalogo: readonly LocalServiceCatalogRow[],
  hoje: string = dataHojeEmBrasilia(),
): ValorDosMateriais {
  let somaAplicado = 0;
  let temAplicado = false;
  let somaSobra = 0;
  let temSobra = false;
  let comPreco = 0;
  let semPreco = 0;

  for (const material of materiais) {
    if (!material.materialNome.trim()) {
      continue;
    }
    const preco = precoLocalDoMaterial(material, catalogo, hoje);
    if (preco === null) {
      semPreco += 1;
      continue;
    }
    comPreco += 1;

    const aplicada = asNumber(material.quantidadeAplicada);
    if (aplicada !== null && aplicada >= 0) {
      somaAplicado += aplicada * preco;
      temAplicado = true;
    }
    // A sobra usa a mesma conta do card "Sobra calculada" logo acima; a
    // negativa (aplicou mais do que usinou) não vira dinheiro desperdiçado.
    const sobra = calcularSobraMaterial(material);
    if (sobra !== null && sobra > 0) {
      somaSobra += sobra * preco;
      temSobra = true;
    }
  }

  return {
    valorAplicado: temAplicado ? round2(somaAplicado) : null,
    valorSobra: temSobra ? round2(somaSobra) : null,
    materiaisComPreco: comPreco,
    materiaisSemPreco: semPreco,
  };
}
