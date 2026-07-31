import { useCallback, useEffect, useMemo, useState } from "react";

import { SYNC_COMPLETED_EVENT } from "../../../lib/sync/syncEvents";
import { LeafletTrechoMap, type PontoGeografico } from "./LeafletTrechoMap";
import { OperationalMap } from "./OperationalMap";
import {
  buildOperationalFeatureCollection,
  comprimentoAproximadoM,
  isValidWorksiteCoordinate,
  type OperationalFeatureCollection,
  type WorksiteMapPoint,
} from "./mapGeometry";
import { carregarMapaObra, type LeituraMapaObra } from "./obraMapApi";
import { registrarTrechoDesenhado } from "./obraGeometriaMutations";
import "./RodoviaWorkspace.css";

interface RodoviaWorkspaceProps {
  obra: WorksiteMapPoint;
  /** Somente Alfa pode desenhar o trecho contratual. */
  podeDesenhar: boolean;
}

type EstadoLeitura =
  | { fase: "carregando" }
  | { fase: "pronto"; leitura: LeituraMapaObra }
  | { fase: "erro"; mensagem: string };

function primeiraCoordenada(
  colecao: OperationalFeatureCollection,
): [number, number] | null {
  function visitar(valor: unknown): [number, number] | null {
    if (
      Array.isArray(valor) &&
      valor.length >= 2 &&
      typeof valor[0] === "number" &&
      typeof valor[1] === "number"
    ) {
      return [valor[0], valor[1]];
    }
    if (Array.isArray(valor)) {
      for (const filho of valor) {
        const encontrado = visitar(filho);
        if (encontrado) return encontrado;
      }
    }
    return null;
  }

  for (const feature of colecao.features) {
    const encontrado = visitar(feature.geometry.coordinates);
    if (encontrado) return encontrado;
  }
  return null;
}

function formatarInstante(valor: string | null): string {
  if (!valor) {
    return "ainda não sincronizado";
  }
  const data = new Date(valor);
  if (Number.isNaN(data.getTime())) {
    return "ainda não sincronizado";
  }
  return `${data.toLocaleDateString("pt-BR")} às ${data.toLocaleTimeString(
    "pt-BR",
    { hour: "2-digit", minute: "2-digit" },
  )}`;
}

/**
 * Mapa da rodovia em duas metades.
 *
 * À esquerda o basemap de satélite do provider configurado; à direita o Leaflet
 * sobre a malha aberta, que continua utilizável mesmo sem chave de provider. Os
 * dois leem exatamente a mesma coleção autoritativa de geometrias, e a leitura
 * se atualiza a cada rodada de sincronização.
 */
export function RodoviaWorkspace({ obra, podeDesenhar }: RodoviaWorkspaceProps) {
  const [estado, setEstado] = useState<EstadoLeitura>({ fase: "carregando" });
  const [modoDesenho, setModoDesenho] = useState<"INATIVO" | "TRECHO">(
    "INATIVO",
  );
  const [pontosMarcados, setPontosMarcados] = useState(0);
  const [aviso, setAviso] = useState<string | null>(null);
  const [ciclo, setCiclo] = useState(0);

  const recarregar = useCallback(() => {
    setCiclo((anterior) => anterior + 1);
  }, []);

  // Cada rodada de sincronização automática relê as camadas autoritativas, que
  // é como o mapa se mantém atual sem canal dedicado de tempo real.
  useEffect(() => {
    window.addEventListener(SYNC_COMPLETED_EVENT, recarregar);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, recarregar);
    };
  }, [recarregar]);

  useEffect(() => {
    let cancelado = false;
    carregarMapaObra(obra)
      .then((leitura) => {
        if (!cancelado) setEstado({ fase: "pronto", leitura });
      })
      .catch((motivo: unknown) => {
        if (cancelado) return;
        setEstado({
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
  }, [obra, ciclo]);

  const leitura = estado.fase === "pronto" ? estado.leitura : null;
  const worksite = leitura?.dados.obra ?? obra;
  const colecao = useMemo(
    () =>
      buildOperationalFeatureCollection(worksite, leitura?.dados.features ?? []),
    [worksite, leitura?.dados.features],
  );
  const centro = useMemo(
    () =>
      isValidWorksiteCoordinate(worksite.latitude, worksite.longitude)
        ? ([worksite.longitude, worksite.latitude] as [number, number])
        : primeiraCoordenada(colecao),
    [worksite, colecao],
  );

  // Soma o comprimento das linhas persistidas: é a extensão que a obra
  // realmente tem desenhada, distinta da extensão medida nos RDOs.
  const extensaoTrecho = useMemo(() => {
    const total = colecao.features.reduce((acumulado, feature) => {
      const comprimento = comprimentoAproximadoM(feature.geometry);
      return comprimento === null ? acumulado : acumulado + comprimento;
    }, 0);
    return total > 0 ? total : null;
  }, [colecao.features]);

  const aoDesenharTrecho = useCallback(
    async (pontos: PontoGeografico[]) => {
      try {
        await registrarTrechoDesenhado({
          obraId: obra.id,
          objetoId: obra.id,
          pontos,
          propriedades: { nome: `Trecho de ${obra.nome}` },
        });
        setAviso(
          "Trecho registrado neste dispositivo. Ele sobe sozinho na próxima sincronização.",
        );
        setModoDesenho("INATIVO");
        setPontosMarcados(0);
        recarregar();
      } catch (motivo: unknown) {
        setAviso(
          motivo instanceof Error
            ? motivo.message
            : "Não foi possível registrar o trecho.",
        );
      }
    },
    [recarregar, obra.id, obra.nome],
  );

  return (
    <section className="rodovia-workspace" aria-labelledby="rodovia-workspace-title">
      <header className="rodovia-workspace-header">
        <div>
          <p className="eyebrow">Mapa da rodovia</p>
          <h3 id="rodovia-workspace-title">{worksite.nome}</h3>
          <span>
            última atualização em{" "}
            {formatarInstante(leitura?.obtidoEm ?? null)}
            {leitura?.origem === "CACHE_LOCAL"
              ? " · dados do dispositivo, sem rede"
              : ""}
          </span>
        </div>
        {podeDesenhar ? (
          <div className="rodovia-workspace-acoes">
            <button
              type="button"
              className={
                modoDesenho === "TRECHO"
                  ? "rodovia-desenho-botao rodovia-desenho-botao--ativo"
                  : "rodovia-desenho-botao"
              }
              aria-pressed={modoDesenho === "TRECHO"}
              onClick={() => {
                setAviso(null);
                setPontosMarcados(0);
                setModoDesenho((atual) =>
                  atual === "TRECHO" ? "INATIVO" : "TRECHO",
                );
              }}
            >
              {modoDesenho === "TRECHO"
                ? `Cancelar desenho (${pontosMarcados}/2)`
                : "Desenhar trecho"}
            </button>
          </div>
        ) : null}
      </header>

      {centro ? (
        <dl className="rodovia-workspace-ficha">
          <div>
            <dt>Coordenada da obra</dt>
            <dd>
              {centro[1].toFixed(6)}, {centro[0].toFixed(6)}
              <small>
                {isValidWorksiteCoordinate(
                  worksite.latitude,
                  worksite.longitude,
                )
                  ? " · cadastro da obra"
                  : " · derivada da geometria"}
              </small>
            </dd>
          </div>
          <div>
            <dt>Extensão georreferenciada</dt>
            <dd>
              {extensaoTrecho !== null
                ? `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 0,
                  }).format(extensaoTrecho)} m`
                : "—"}
            </dd>
          </div>
          <div>
            <dt>Camadas ativas</dt>
            <dd>{colecao.features.length}</dd>
          </div>
        </dl>
      ) : null}

      {aviso ? <p className="rodovia-workspace-aviso">{aviso}</p> : null}
      {estado.fase === "erro" ? (
        <p className="rodovia-workspace-aviso">
          {estado.mensagem} Nenhuma camada foi encontrada neste dispositivo para
          esta obra.
        </p>
      ) : null}

      <div className="rodovia-workspace-split">
        <div className="rodovia-workspace-painel">
          <OperationalMap obra={worksite} />
        </div>
        <div className="rodovia-workspace-painel">
          {centro ? (
            <LeafletTrechoMap
              features={colecao}
              center={centro}
              modo={modoDesenho}
              onTrechoDesenhado={aoDesenharTrecho}
              onPontoCapturado={setPontosMarcados}
            />
          ) : (
            <div className="rodovia-workspace-vazio">
              <strong>Obra ainda não georreferenciada</strong>
              <p>
                {podeDesenhar
                  ? "Use “Desenhar trecho” para marcar o início e o fim da obra sobre a rodovia, ou registre um ponto pela captura de campo."
                  : "Nenhuma coordenada foi registrada para esta obra. A equipe de campo pode registrar um ponto pelo RDO."}
              </p>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
