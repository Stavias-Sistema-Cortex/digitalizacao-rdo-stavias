import { useEffect, useMemo, useState } from "react";

import { LOCAL_MUTATION_QUEUED_EVENT } from "../../../lib/sync/localMutationCoordinator";
import { SYNC_COMPLETED_EVENT } from "../../../lib/sync/syncEvents";
import { RodoviaWorkspace } from "../map/RodoviaWorkspace";
import type { WorksiteMapPoint } from "../map/mapGeometry";
import type { EnderecoDaObra } from "../map/enquadramentoAproximado";
import { carregarTrechoDaObra, type LeituraTrecho } from "./obraTrechoApi";
import { TrechoEsquematico } from "./TrechoEsquematico";
import { TrechoReceitaSection } from "./TrechoReceitaSection";
import { TrechoPeriodoFiltro } from "./TrechoPeriodoFiltro";
import { TrechoResumo } from "./TrechoResumo";
import { recortarProjecao, type Periodo } from "./trechoGeometry";
import "./TrechoEsquematico.css";

interface ObraTrechoSectionProps {
  obra: WorksiteMapPoint;
  /** Somente Alfa desenha o trecho contratual sobre o mapa. */
  podeDesenhar: boolean;
  /**
   * Operabilidade conhecida localmente, usada enquanto a projeção não chega e
   * quando nem a rede nem o cache respondem.
   */
  operavelLocalmente: boolean;
  /** Endereço cadastral, só para enquadrar o mapa antes da georreferência. */
  endereco?: EnderecoDaObra;
  /** Período aberto de saída, por exemplo ao retomar uma leitura salva. */
  periodoInicial?: Periodo;
}

type Estado =
  | { fase: "carregando" }
  | { fase: "pronto"; leitura: LeituraTrecho }
  | { fase: "erro"; mensagem: string };

const SEM_PERIODO: Periodo = { de: null, ate: null };

function procedencia(leitura: LeituraTrecho): string | null {
  if (leitura.origem === "REDE") {
    return null;
  }
  if (leitura.origem === "DISPOSITIVO") {
    return "Somente lançamentos deste aparelho";
  }
  const data = new Date(leitura.obtidoEm);
  if (Number.isNaN(data.getTime())) {
    return "Dados do dispositivo, sem rede";
  }
  return `Dados do dispositivo, de ${data.toLocaleDateString(
    "pt-BR",
  )} às ${data.toLocaleTimeString("pt-BR", {
    hour: "2-digit",
    minute: "2-digit",
  })}`;
}

/**
 * Acompanhamento do trecho de rodovia de uma obra.
 *
 * Obra operável recebe o mapa dividido, o filtro de período e o esquemático
 * vivo. Obra desativada ou arquivada tem a camada viva desligada e fica apenas
 * com o resumo do que foi executado — lido das mesmas fontes persistidas.
 *
 * A projeção completa é buscada uma vez e recortada no dispositivo a cada
 * mudança de período, o que mantém a navegação pelos dias utilizável offline.
 */
export function ObraTrechoSection({
  obra,
  podeDesenhar,
  operavelLocalmente,
  endereco,
  periodoInicial,
}: ObraTrechoSectionProps) {
  const [estado, setEstado] = useState<Estado>({ fase: "carregando" });
  const [ciclo, setCiclo] = useState(0);
  const [periodo, setPeriodo] = useState<Periodo>(
    periodoInicial ?? SEM_PERIODO,
  );
  const [diaSelecionado, setDiaSelecionado] = useState<string | null>(null);

  // Cada rodada de sincronização automática relê a projeção, que é como o
  // acompanhamento se mantém atual sem canal dedicado de tempo real. A escrita
  // local também relê: o RDO salvo agora neste aparelho desenha no trecho
  // imediatamente, sem esperar a fila subir.
  useEffect(() => {
    function aoMudar() {
      setCiclo((anterior) => anterior + 1);
    }
    window.addEventListener(SYNC_COMPLETED_EVENT, aoMudar);
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, aoMudar);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, aoMudar);
      window.removeEventListener(LOCAL_MUTATION_QUEUED_EVENT, aoMudar);
    };
  }, []);

  useEffect(() => {
    let cancelado = false;
    carregarTrechoDaObra({
      id: obra.id,
      nome: obra.nome,
      operavel: operavelLocalmente,
    })
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
              : "Projeção do trecho indisponível.",
        });
      });
    return () => {
      cancelado = true;
    };
  }, [obra.id, obra.nome, operavelLocalmente, ciclo]);

  const leitura = estado.fase === "pronto" ? estado.leitura : null;
  const recorte = useMemo<Periodo>(
    () =>
      diaSelecionado
        ? { de: diaSelecionado, ate: diaSelecionado }
        : periodo,
    [diaSelecionado, periodo],
  );
  const projecao = useMemo(
    () =>
      leitura ? recortarProjecao(leitura.projecao, recorte) : null,
    [leitura, recorte],
  );

  const operavel = projecao ? projecao.operavel : operavelLocalmente;

  if (!operavel) {
    return (
      <div className="obras-trecho">
        {projecao && leitura ? (
          <TrechoResumo
            projecao={projecao}
            procedencia={procedencia(leitura)}
          />
        ) : (
          <section className="trecho-esquematico">
            <header className="trecho-esquematico-header">
              <div>
                <p className="eyebrow">Obra desativada</p>
                <h3>Resumo do trecho</h3>
                <span>
                  {estado.fase === "carregando"
                    ? "Consultando o histórico executado…"
                    : "Histórico indisponível neste dispositivo."}
                </span>
              </div>
            </header>
          </section>
        )}
      </div>
    );
  }

  return (
    <div className="obras-trecho">
      <RodoviaWorkspace
        key={obra.id}
        obra={obra}
        podeDesenhar={podeDesenhar}
        endereco={endereco}
      />
      {projecao && leitura ? (
        <TrechoEsquematico
          projecao={projecao}
          procedencia={procedencia(leitura)}
          filtro={
            <TrechoPeriodoFiltro
              disponivel={leitura.projecao.periodoDisponivel}
              valor={periodo}
              dias={leitura.projecao.diasExecutados}
              diaSelecionado={diaSelecionado}
              onAlterarPeriodo={(proximo) => {
                setDiaSelecionado(null);
                setPeriodo(proximo);
              }}
              onSelecionarDia={setDiaSelecionado}
            />
          }
        />
      ) : (
        <section className="trecho-esquematico">
          <header className="trecho-esquematico-header">
            <div>
              <p className="eyebrow">Trecho em execução</p>
              <h3>Esquemático da rodovia</h3>
              <span>
                {estado.fase === "carregando"
                  ? "Consultando o controle geométrico dos RDOs…"
                  : estado.fase === "erro"
                    ? estado.mensagem
                    : ""}
              </span>
            </div>
          </header>
        </section>
      )}
      <TrechoReceitaSection obraId={obra.id} periodo={recorte} />
    </div>
  );
}
