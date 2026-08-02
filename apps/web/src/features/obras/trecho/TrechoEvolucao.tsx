import { useEffect, useRef } from "react";

import {
  LARGURA_DO_DIA_PX,
  acumuladoAteODia,
  indiceDoDiaNaRolagem,
  rolagemDoIndice,
} from "./trechoEvolucao";
import { formatarData, type DiaExecutado } from "./trechoGeometry";

interface TrechoEvolucaoProps {
  dias: DiaExecutado[];
  /** Dia sob a agulha; nulo = rodovia completa, sem recorte. */
  diaAtivo: string | null;
  onMudarDia: (dia: string | null) => void;
}

function diaDaSemana(data: string): string {
  const [ano, mes, dia] = data.split("-").map(Number);
  return new Date(ano, mes - 1, dia)
    .toLocaleDateString("pt-BR", { weekday: "long" })
    .replace("-feira", "");
}

function numero(valor: number): string {
  return new Intl.NumberFormat("pt-BR", {
    maximumFractionDigits: 0,
  }).format(valor);
}

/**
 * A rodovia rolável no tempo.
 *
 * Arrastar o trilho para a esquerda recua o calendário dia a dia; o
 * esquemático e os mapas mostram o que estava executado até o dia sob a
 * agulha — a obra crescendo como num time-lapse. Só existem no trilho os dias
 * em que algum RDO lançou produção: dia parado não vira quadro vazio.
 *
 * A rolagem é nativa (toque, roda, trackpad, teclado nas setas), e a conta
 * posição→dia é uma divisão por célula de largura fixa — sem medir layout,
 * o que a mantém correta em qualquer tela e testável sem navegador.
 */
export function TrechoEvolucao({
  dias,
  diaAtivo,
  onMudarDia,
}: TrechoEvolucaoProps) {
  const trilhoRef = useRef<HTMLDivElement | null>(null);
  // Booleano, e não o id do quadro: o id volta depois de o callback já poder
  // ter rodado, e a atribuição tardia sobrescreveria a limpeza.
  const aguardandoQuadro = useRef(false);
  // Rolagem programática (botões, sincronização externa) não deve reemitir
  // onMudarDia pelo evento de scroll que ela mesma causou. Guardar o destino
  // — e não uma flag — deixa a rolagem real do usuário passar mesmo que o
  // navegador nunca emita o evento programático.
  const destinoProgramatico = useRef<number | null>(null);

  const indiceAtivo = diaAtivo === null
    ? dias.length - 1
    : dias.findIndex((dia) => dia.data === diaAtivo);
  const diaSobAgulha = indiceAtivo >= 0 ? dias[indiceAtivo] : null;

  useEffect(() => {
    const trilho = trilhoRef.current;
    if (!trilho || indiceAtivo < 0) {
      return;
    }
    const destino = rolagemDoIndice(indiceAtivo);
    if (Math.abs(trilho.scrollLeft - destino) < 1) {
      return;
    }
    destinoProgramatico.current = destino;
    trilho.scrollLeft = destino;
  }, [indiceAtivo, dias.length]);

  if (dias.length === 0) {
    return null;
  }

  function aoRolar() {
    if (aguardandoQuadro.current) {
      return;
    }
    const agendar =
      typeof requestAnimationFrame === "function"
        ? requestAnimationFrame
        : (tarefa: () => void) => {
            tarefa();
            return 0;
          };
    aguardandoQuadro.current = true;
    agendar(() => {
      aguardandoQuadro.current = false;
      const trilho = trilhoRef.current;
      if (!trilho) {
        return;
      }
      const posicao = trilho.scrollLeft;
      if (destinoProgramatico.current !== null) {
        const alcancado =
          Math.abs(posicao - destinoProgramatico.current) < 1;
        destinoProgramatico.current = null;
        if (alcancado) {
          return;
        }
      }
      const indice = indiceDoDiaNaRolagem(dias.length, posicao);
      if (indice < 0) {
        return;
      }
      const dia = dias[indice].data;
      const ultimo = indice === dias.length - 1;
      const proximo = ultimo ? null : dia;
      if (proximo !== diaAtivo) {
        onMudarDia(proximo);
      }
    });
  }

  function irPara(indice: number) {
    const limitado = Math.min(Math.max(indice, 0), dias.length - 1);
    onMudarDia(
      limitado === dias.length - 1 ? null : dias[limitado].data,
    );
  }

  const acumulado = diaSobAgulha
    ? acumuladoAteODia(dias, diaSobAgulha.data)
    : null;
  const noFim = indiceAtivo === dias.length - 1;

  return (
    <div className="trecho-evolucao">
      <div className="trecho-evolucao-cabecalho">
        <div
          className="trecho-evolucao-calendario"
          role="status"
          aria-live="polite"
        >
          {diaSobAgulha ? (
            <>
              <strong>{formatarData(diaSobAgulha.data)}</strong>
              <span>{diaDaSemana(diaSobAgulha.data)}</span>
            </>
          ) : null}
        </div>
        {acumulado ? (
          <p className="trecho-evolucao-acumulado">
            {noFim
              ? "Rodovia completa até aqui: "
              : "Executado até este dia: "}
            {numero(acumulado.extensaoM)} m em {acumulado.dias}{" "}
            {acumulado.dias === 1 ? "dia" : "dias"} ·{" "}
            {acumulado.totalRdos}{" "}
            {acumulado.totalRdos === 1 ? "RDO" : "RDOs"}
          </p>
        ) : null}
        <div className="trecho-evolucao-controles">
          <button
            type="button"
            aria-label="Dia anterior"
            disabled={indiceAtivo <= 0}
            onClick={() => irPara(indiceAtivo - 1)}
          >
            ‹
          </button>
          <button
            type="button"
            aria-label="Dia seguinte"
            disabled={noFim}
            onClick={() => irPara(indiceAtivo + 1)}
          >
            ›
          </button>
          <button
            type="button"
            className="trecho-evolucao-hoje"
            disabled={noFim}
            onClick={() => onMudarDia(null)}
          >
            Ver tudo
          </button>
        </div>
      </div>

      <div className="trecho-evolucao-janela">
        <div className="trecho-evolucao-agulha" aria-hidden="true" />
        <div
          ref={trilhoRef}
          className="trecho-evolucao-trilho"
          role="slider"
          tabIndex={0}
          aria-label="Rolar a rodovia no tempo"
          aria-valuemin={0}
          aria-valuemax={dias.length - 1}
          aria-valuenow={Math.max(indiceAtivo, 0)}
          aria-valuetext={
            diaSobAgulha ? formatarData(diaSobAgulha.data) : undefined
          }
          onScroll={aoRolar}
          onKeyDown={(evento) => {
            if (evento.key === "ArrowLeft") {
              evento.preventDefault();
              irPara(indiceAtivo - 1);
            }
            if (evento.key === "ArrowRight") {
              evento.preventDefault();
              irPara(indiceAtivo + 1);
            }
          }}
        >
          <div
            className="trecho-evolucao-faixa"
            style={{
              width: `${dias.length * LARGURA_DO_DIA_PX}px`,
            }}
          >
            {dias.map((dia, indice) => (
              <button
                type="button"
                key={dia.data}
                tabIndex={-1}
                className={
                  indice === indiceAtivo
                    ? "trecho-evolucao-dia trecho-evolucao-dia--ativo"
                    : "trecho-evolucao-dia"
                }
                style={{ width: `${LARGURA_DO_DIA_PX}px` }}
                title={`${dia.totalRdos} RDO(s) · ${numero(dia.extensaoM)} m`}
                onClick={() => irPara(indice)}
              >
                <strong>{formatarData(dia.data).slice(0, 5)}</strong>
                <small>{numero(dia.extensaoM)} m</small>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
