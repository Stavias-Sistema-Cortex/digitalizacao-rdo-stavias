import { useMemo, type ReactNode } from "react";

import {
  calcularEscalaKm,
  estadoDoSegmento,
  formatarData,
  formatarKm,
  formatarNumeroKm,
  formatarPeriodo,
  ladosDoCanteiro,
  marcosDeLimite,
  marcosKm,
  pistasDoTrecho,
  rotuloDoSegmento,
  segmentosPosicionaveis,
  type BlocoSegmento,
  type EscalaKm,
  type PistaEsquematica,
  type ProjecaoTrecho,
} from "./trechoGeometry";
import "./TrechoEsquematico.css";

interface TrechoEsquematicoProps {
  projecao: ProjecaoTrecho;
  /** Rótulo de procedência mostrado no cabeçalho, quando houver. */
  procedencia?: string | null;
  /** Controle de período, renderizado abaixo do cabeçalho. */
  filtro?: ReactNode;
}

const ESTADO_LABEL: Record<string, string> = {
  PROGRAMADO: "Programado",
  RASCUNHO: "RDO em preenchimento",
  EXECUTADO: "Executado",
  VALIDADO: "Validado",
  REJEITADO: "Rejeitado",
};

function Bloco({ bloco }: { bloco: BlocoSegmento }) {
  const estado = estadoDoSegmento(bloco.segmento);
  const detalhe = [
    bloco.segmento.numeroRdo ? `RDO ${bloco.segmento.numeroRdo}` : null,
    bloco.segmento.data ? formatarData(bloco.segmento.data) : null,
    `${formatarKm(bloco.segmento.kmInicial)} → ${formatarKm(
      bloco.segmento.kmFinal,
    )}`,
    ESTADO_LABEL[estado],
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <div
      className={`trecho-bloco trecho-bloco--${estado.toLowerCase()}`}
      style={{ left: `${bloco.inicio}%`, width: `${bloco.largura}%` }}
      title={`${rotuloDoSegmento(bloco.segmento)} — ${detalhe}`}
    >
      <span className="trecho-bloco-rotulo">
        <span>{rotuloDoSegmento(bloco.segmento)}</span>
        {bloco.segmento.data ? (
          <em>{formatarData(bloco.segmento.data).slice(0, 5)}</em>
        ) : null}
      </span>
    </div>
  );
}

function Pista({ pista }: { pista: PistaEsquematica }) {
  return (
    <div className="trecho-pista">
      <span className="trecho-pista-nome">
        {pista.sentido} · {pista.faixa}
      </span>
      <div className="trecho-pista-rolagem">
        {pista.blocos.map((bloco) => (
          <Bloco key={bloco.segmento.id} bloco={bloco} />
        ))}
      </div>
    </div>
  );
}

function Regua({
  escala,
  projecao,
}: {
  escala: EscalaKm;
  projecao: ProjecaoTrecho;
}) {
  const marcos = useMemo(() => marcosKm(escala), [escala]);
  const limites = useMemo(
    () => marcosDeLimite(escala, projecao.segmentos),
    [escala, projecao.segmentos],
  );

  return (
    <div className="trecho-regua" aria-hidden="true">
      {marcos.map((marco) => (
        <span
          key={marco.km}
          className={`trecho-marco${bordaDaRegua(marco.posicao)}`}
          style={{ left: `${marco.posicao}%` }}
        >
          km {marco.km}
        </span>
      ))}
      {limites.map((marco) => (
        <span
          key={`limite-${marco.limite}`}
          className={`trecho-marco trecho-marco--limite${bordaDaRegua(
            marco.posicao,
          )}`}
          style={{ left: `${marco.posicao}%` }}
        >
          <small>{marco.limite === "INICIO" ? "início" : "fim"}</small>
          km {formatarNumeroKm(marco.km)}
        </span>
      ))}
    </div>
  );
}

/**
 * Um rótulo centrado na posição vaza para fora da régua nos extremos. Nas
 * bordas ele é ancorado pela lateral em vez de pelo centro.
 */
function bordaDaRegua(posicao: number): string {
  if (posicao <= 1) return " trecho-marco--borda-inicial";
  if (posicao >= 99) return " trecho-marco--borda-final";
  return "";
}

export function TrechoEsquematico({
  projecao,
  procedencia,
  filtro,
}: TrechoEsquematicoProps) {
  const escala = useMemo(
    () => calcularEscalaKm(projecao.segmentos),
    [projecao.segmentos],
  );
  const pistas = useMemo(
    () => (escala ? pistasDoTrecho(projecao.segmentos, escala) : []),
    [escala, projecao.segmentos],
  );
  const lados = useMemo(() => ladosDoCanteiro(pistas), [pistas]);
  const semMarcacao =
    projecao.segmentos.length -
    segmentosPosicionaveis(projecao.segmentos).length;
  const temFiltroDePeriodo = Boolean(
    projecao.periodoAplicado.de || projecao.periodoAplicado.ate,
  );
  // A legenda lista só os estados que a obra realmente tem no período.
  const estadosPresentes = useMemo(
    () =>
      [
        ...new Set(
          segmentosPosicionaveis(projecao.segmentos).map(estadoDoSegmento),
        ),
      ].sort(),
    [projecao.segmentos],
  );

  return (
    <section
      className="trecho-esquematico"
      aria-labelledby="trecho-esquematico-title"
    >
      <header className="trecho-esquematico-header">
        <div>
          <p className="eyebrow">Trecho em execução</p>
          <h3 id="trecho-esquematico-title">
            {projecao.rodovia ?? "Rodovia não informada"}
          </h3>
          <span>
            {escala
              ? `${formatarKm(projecao.kmMin)} a ${formatarKm(projecao.kmMax)}`
              : "Sem marcação quilométrica registrada"}
            {" · "}
            {formatarPeriodo(projecao.periodoAplicado)}
          </span>
        </div>
        {procedencia ? (
          <p className="trecho-esquematico-procedencia">{procedencia}</p>
        ) : null}
      </header>

      {filtro}

      {escala ? (
        <div className="trecho-pista-quadro">
          <Regua escala={escala} projecao={projecao} />
          <div className="trecho-acostamento">Acostamento</div>
          {lados.superior.map((pista) => (
            <Pista key={pista.id} pista={pista} />
          ))}
          {lados.temCanteiro ? (
            <div className="trecho-canteiro">Canteiro central divisor</div>
          ) : null}
          {lados.inferior.map((pista) => (
            <Pista key={pista.id} pista={pista} />
          ))}
          <div className="trecho-acostamento">Acostamento</div>
        </div>
      ) : (
        <div className="trecho-esquematico-vazio">
          <strong>
            {temFiltroDePeriodo
              ? "Nenhum trecho executado neste período"
              : "Nenhum trecho posicionável ainda"}
          </strong>
          <p>
            {temFiltroDePeriodo
              ? "Amplie o período ou volte para todo o intervalo para ver a execução registrada."
              : "O esquemático é desenhado a partir do controle geométrico e dos serviços executados dos RDOs desta obra. Lance km inicial e km final no RDO para que o trecho apareça aqui."}
          </p>
        </div>
      )}

      {escala ? (
        <ul className="trecho-legenda" aria-label="Estados de execução">
          {estadosPresentes.map((estado) => (
            <li key={estado}>
              <span
                className={`trecho-legenda-marca trecho-bloco--${estado.toLowerCase()}`}
                aria-hidden="true"
              />
              {ESTADO_LABEL[estado]}
            </li>
          ))}
        </ul>
      ) : null}

      <footer className="trecho-esquematico-footer">
        <dl>
          <div>
            <dt>Extensão executada</dt>
            <dd>
              {projecao.resumo.extensaoTotalM > 0
                ? `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 0,
                  }).format(projecao.resumo.extensaoTotalM)} m`
                : "—"}
            </dd>
          </div>
          <div>
            <dt>RDOs com produção</dt>
            <dd>{projecao.resumo.totalRdos}</dd>
          </div>
          <div>
            <dt>Massa aplicada</dt>
            <dd>
              {projecao.resumo.massaTotalTonelada > 0
                ? `${new Intl.NumberFormat("pt-BR", {
                    maximumFractionDigits: 1,
                  }).format(projecao.resumo.massaTotalTonelada)} t`
                : "—"}
            </dd>
          </div>
        </dl>
        <div className="trecho-esquematico-notas">
          {projecao.resumo.totalRascunhos > 0 ? (
            <small>
              {projecao.resumo.totalRascunhos} lançamento(s) em RDO ainda
              aberto, fora do consolidado.
            </small>
          ) : null}
          {semMarcacao > 0 ? (
            <small>
              {semMarcacao} lançamento(s) sem km inicial e final não puderam ser
              posicionados.
            </small>
          ) : null}
        </div>
      </footer>
    </section>
  );
}
