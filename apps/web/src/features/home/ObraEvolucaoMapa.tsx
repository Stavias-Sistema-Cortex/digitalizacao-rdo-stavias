import { useEffect, useMemo, useState } from "react";

import type { ObraLocalRecord } from "../../lib/db/db.types";
import { LeafletTrechoMap } from "../obras/map/LeafletTrechoMap";
import {
  buildOperationalFeatureCollection,
  isValidWorksiteCoordinate,
  limitesDaColecao,
  type OperationalFeatureCollection,
} from "../obras/map/mapGeometry";
import { carregarMapaObra, type LeituraMapaObra } from "../obras/map/obraMapApi";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import {
  janelaDoPeriodo,
  recortarEvolucao,
} from "./evolucaoDaObra";
import type { ChartPeriod } from "./progressSeries";

interface ObraEvolucaoMapaProps {
  obra: ObraLocalRecord;
  /** Mesma janela do gráfico de avanço: os dois contam a mesma história. */
  period: ChartPeriod;
}

type Estado =
  | { fase: "carregando" }
  | { fase: "pronto"; leitura: LeituraMapaObra }
  | { fase: "vazio" };

function primeiraCoordenada(
  colecao: OperationalFeatureCollection,
): [number, number] | null {
  const limites = limitesDaColecao(colecao);
  if (!limites) return null;
  return [
    (limites.oeste + limites.leste) / 2,
    (limites.sul + limites.norte) / 2,
  ];
}

/**
 * Evolução geoespacial da obra em foco, na Home.
 *
 * Lê exatamente as mesmas camadas autoritativas do mapa da obra —
 * cache-through, funcionais offline — e recorta pela janela que o gráfico de
 * avanço já usa. Nenhuma busca própria: uma segunda fonte aqui deixaria a
 * Home discordando da aba de Obras sobre a mesma geometria.
 */
export function ObraEvolucaoMapa({ obra, period }: ObraEvolucaoMapaProps) {
  const [estado, setEstado] = useState<Estado>({ fase: "carregando" });
  const [ciclo, setCiclo] = useState(0);

  useEffect(() => {
    const recarregar = () => setCiclo((anterior) => anterior + 1);
    window.addEventListener(SYNC_COMPLETED_EVENT, recarregar);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, recarregar);
    };
  }, []);

  const { id, nome, latitude, longitude } = obra;
  useEffect(() => {
    let cancelado = false;
    carregarMapaObra({ id, nome, latitude, longitude })
      .then((leitura) => {
        if (!cancelado) setEstado({ fase: "pronto", leitura });
      })
      .catch(() => {
        // Sem camada alguma neste dispositivo o card diz isso, em vez de
        // fingir um mapa vazio que pareceria obra sem atividade.
        if (!cancelado) setEstado({ fase: "vazio" });
      });
    return () => {
      cancelado = true;
    };
  }, [id, nome, latitude, longitude, ciclo]);

  const colecaoCompleta = useMemo(() => {
    if (estado.fase !== "pronto") return null;
    return buildOperationalFeatureCollection(
      estado.leitura.dados.obra,
      estado.leitura.dados.features,
    );
  }, [estado]);

  const janela = useMemo(() => janelaDoPeriodo(period), [period]);
  const colecao = useMemo(
    () => (colecaoCompleta ? recortarEvolucao(colecaoCompleta, janela) : null),
    [colecaoCompleta, janela],
  );

  const centro = useMemo(() => {
    if (!colecao) return null;
    const worksite = estado.fase === "pronto"
      ? estado.leitura.dados.obra
      : null;
    return worksite &&
        isValidWorksiteCoordinate(worksite.latitude, worksite.longitude)
      ? ([worksite.longitude, worksite.latitude] as [number, number])
      : primeiraCoordenada(colecao);
  }, [colecao, estado]);

  if (estado.fase === "carregando") {
    return (
      <p className="home-evolucao-vazio" role="status">
        Carregando as camadas da obra…
      </p>
    );
  }
  if (estado.fase === "vazio" || !colecao || !centro) {
    return (
      <p className="home-evolucao-vazio">
        Obra ainda sem geometria registrada. O mapa aparece quando um trecho
        for desenhado ou uma posição for capturada em campo.
      </p>
    );
  }

  const totalNoPeriodo = colecao.features.length;
  const totalGeral = colecaoCompleta?.features.length ?? totalNoPeriodo;

  return (
    <div className="home-evolucao-mapa">
      <LeafletTrechoMap features={colecao} center={centro} modo="INATIVO" />
      <small role="status">
        {janela.inicio
          ? `${totalNoPeriodo} de ${totalGeral} marcação(ões) participaram do período.`
          : `${totalGeral} marcação(ões) em todo o histórico.`}
      </small>
    </div>
  );
}
