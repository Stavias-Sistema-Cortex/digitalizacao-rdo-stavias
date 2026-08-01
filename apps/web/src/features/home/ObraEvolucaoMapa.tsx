import { useEffect, useMemo, useState } from "react";

import type { ObraLocalRecord } from "../../lib/db/db.types";
import { LeafletTrechoMap } from "../obras/map/LeafletTrechoMap";
import { recortarPorJanela } from "../obras/map/filtrosDoMapa";
import {
  buildOperationalFeatureCollection,
  centroDaColecao,
  isValidWorksiteCoordinate,
  type OperationalFeatureCollection,
} from "../obras/map/mapGeometry";
import { carregarMapaObra, type LeituraMapaObra } from "../obras/map/obraMapApi";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import type { JanelaDeEvolucao } from "./evolucaoDaObra";

interface ObraEvolucaoMapaProps {
  obra: ObraLocalRecord;
  /** Janela derivada dos pontos do gráfico: os dois contam a mesma história. */
  janela: JanelaDeEvolucao;
}

type Estado =
  | { fase: "carregando" }
  | { fase: "pronto"; leitura: LeituraMapaObra }
  | { fase: "erro"; mensagem: string };

/** As marcações de trabalho, sem o pino sintético da própria obra. */
function marcacoesDeTrabalho(
  collection: OperationalFeatureCollection,
): number {
  return collection.features.filter(
    (feature) => feature.properties.categoria !== "LOCALIZACAO_OBRA",
  ).length;
}

function formatarInstante(valor: string | null): string | null {
  if (!valor) return null;
  const data = new Date(valor);
  if (Number.isNaN(data.getTime())) return null;
  return `${data.toLocaleDateString("pt-BR")} às ${data.toLocaleTimeString(
    "pt-BR",
    { hour: "2-digit", minute: "2-digit" },
  )}`;
}

/**
 * Evolução geoespacial da obra em foco, na Home.
 *
 * Lê exatamente as mesmas camadas autoritativas do mapa da obra —
 * cache-through, funcionais offline — e recorta pela janela que o gráfico de
 * avanço já exibe. Nenhuma busca própria: uma segunda fonte aqui deixaria a
 * Home discordando da aba de Obras sobre a mesma geometria.
 */
export function ObraEvolucaoMapa({ obra, janela }: ObraEvolucaoMapaProps) {
  const [estado, setEstado] = useState<Estado>({ fase: "carregando" });
  const [ciclo, setCiclo] = useState(0);

  useEffect(() => {
    const recarregar = () => setCiclo((anterior) => anterior + 1);
    window.addEventListener(SYNC_COMPLETED_EVENT, recarregar);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, recarregar);
    };
  }, []);

  // A troca de obra remonta o componente pelo `key` no pai, o que devolve o
  // estado inicial de carga na hora — sem setState síncrono em efeito e sem
  // exibir os trechos de uma obra sob o título de outra.
  const { id, nome, latitude, longitude } = obra;
  useEffect(() => {
    let cancelado = false;
    carregarMapaObra({ id, nome, latitude, longitude })
      .then((leitura) => {
        if (!cancelado) setEstado({ fase: "pronto", leitura });
      })
      .catch((motivo: unknown) => {
        if (cancelado) return;
        setEstado((atual) =>
          // Uma releitura de sincronização que falha não apaga o mapa que já
          // está na tela: o dado anterior continua verdadeiro.
          atual.fase === "pronto"
            ? atual
            : {
                fase: "erro",
                mensagem:
                  motivo instanceof Error
                    ? motivo.message
                    : "Camadas geoespaciais indisponíveis.",
              });
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

  const colecao = useMemo(
    () =>
      colecaoCompleta
        ? recortarPorJanela(colecaoCompleta, janela.inicio)
        : null,
    [colecaoCompleta, janela.inicio],
  );

  const centro = useMemo(() => {
    if (!colecao) return null;
    const worksite = estado.fase === "pronto"
      ? estado.leitura.dados.obra
      : null;
    return worksite &&
        isValidWorksiteCoordinate(worksite.latitude, worksite.longitude)
      ? ([worksite.longitude, worksite.latitude] as [number, number])
      : centroDaColecao(colecao);
  }, [colecao, estado]);

  if (estado.fase === "carregando") {
    return (
      <p className="home-evolucao-vazio" role="status">
        Carregando as camadas da obra…
      </p>
    );
  }
  if (estado.fase === "erro") {
    // Erro não vira "obra sem geometria": dizer a quem está em campo que
    // nada foi registrado, quando a leitura é que falhou, mandaria a pessoa
    // redesenhar um trecho que existe.
    return (
      <p className="home-evolucao-vazio" role="alert">
        Não foi possível ler as camadas agora. {estado.mensagem} A aba de
        Obras mostra o detalhe desta leitura.
      </p>
    );
  }

  const totalGeral = colecaoCompleta
    ? marcacoesDeTrabalho(colecaoCompleta)
    : 0;
  if (!colecao || !centro || totalGeral === 0) {
    return (
      <p className="home-evolucao-vazio">
        Obra ainda sem geometria registrada. O mapa aparece quando um trecho
        for desenhado ou uma posição for capturada em campo.
      </p>
    );
  }

  const totalNoPeriodo = marcacoesDeTrabalho(colecao);
  if (janela.inicio && totalNoPeriodo === 0) {
    // Existe geometria, só não neste período: a mensagem de "sem geometria"
    // aqui seria falsa e contradita pelo próprio botão "Tudo" ao lado.
    return (
      <p className="home-evolucao-vazio">
        Nenhuma marcação participou do período selecionado. Amplie o período
        para ver o histórico registrado.
      </p>
    );
  }

  const origem = estado.leitura.origem;
  const obtidoEm = formatarInstante(estado.leitura.obtidoEm);

  return (
    <div className="home-evolucao-mapa">
      <LeafletTrechoMap features={colecao} center={centro} />
      <small role="status">
        {janela.inicio
          ? `${totalNoPeriodo} de ${totalGeral} marcação(ões) participaram do período.`
          : `${totalGeral} marcação(ões) em todo o histórico.`}
        {origem === "CACHE_LOCAL" && obtidoEm
          ? ` Mostrando o que já estava neste aparelho, de ${obtidoEm}.`
          : ""}
      </small>
    </div>
  );
}
