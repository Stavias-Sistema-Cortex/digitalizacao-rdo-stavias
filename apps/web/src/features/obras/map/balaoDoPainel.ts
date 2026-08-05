import type { OperationalFeatureCollection } from "./mapGeometry";

/** O mínimo que o balão de qualquer runtime precisa oferecer. */
export interface BalaoAbrivel {
  remove?(): unknown;
}

/**
 * Mantém um balão por vez e o fecha quando a feição dele sai do mapa.
 *
 * O Leaflet faz isso sozinho: `bindPopup` registra `remove: this.closePopup`,
 * então o balão morre junto com a camada. No MapLibre o balão é um objeto
 * solto, ancorado numa coordenada, que sobrevive ao desaparecimento da feição.
 * Sem este controle, remover um ponto apagava o círculo e deixava o balão
 * parado no mesmo pixel — com a lixeira dentro. Quem removia via a marcação
 * continuar ali e clicava de novo, num laço que não terminava nunca.
 */
export interface RastreadorDeBalao {
  abrir(balao: BalaoAbrivel, geometriaId: string | null): void;
  fecharSeSumiu(features: OperationalFeatureCollection): void;
  fechar(): void;
}

export function idDaFeicao(
  properties: Record<string, unknown> | null | undefined,
): string | null {
  const valor = properties?.geometriaId;
  return typeof valor === "string" && valor ? valor : null;
}

export function rastreadorDeBalao(): RastreadorDeBalao {
  let aberto: { balao: BalaoAbrivel; geometriaId: string | null } | null = null;

  function fechar() {
    aberto?.balao.remove?.();
    aberto = null;
  }

  return {
    fechar,
    abrir: (balao, geometriaId) => {
      // Um por vez: o anterior fica órfão se não for fechado aqui.
      fechar();
      aberto = { balao, geometriaId };
    },
    fecharSeSumiu: (features) => {
      const atual = aberto;
      if (!atual) {
        return;
      }
      /*
       * Balão sem identidade não tem como ser conferido contra a leitura nova.
       * Deixá-lo aberto é menos ruim do que fechar por engano o balão de uma
       * feição que continua no mapa.
       */
      if (atual.geometriaId === null) {
        return;
      }
      const continua = features.features.some(
        (feature) => idDaFeicao(feature.properties) === atual.geometriaId,
      );
      if (!continua) {
        fechar();
      }
    },
  };
}
