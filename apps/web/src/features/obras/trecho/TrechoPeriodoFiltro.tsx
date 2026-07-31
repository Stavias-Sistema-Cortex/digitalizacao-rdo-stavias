import {
  formatarData,
  type DiaExecutado,
  type Periodo,
} from "./trechoGeometry";

interface TrechoPeriodoFiltroProps {
  /** Extensão real do que a obra registrou, usada para limitar os campos. */
  disponivel: Periodo;
  valor: Periodo;
  dias: DiaExecutado[];
  diaSelecionado: string | null;
  onAlterarPeriodo: (periodo: Periodo) => void;
  onSelecionarDia: (data: string | null) => void;
}

function numero(valor: number, casas = 0): string {
  return new Intl.NumberFormat("pt-BR", {
    maximumFractionDigits: casas,
  }).format(valor);
}

function diaDaSemana(data: string): string {
  const [ano, mes, dia] = data.split("-").map(Number);
  return new Date(ano, mes - 1, dia)
    .toLocaleDateString("pt-BR", { weekday: "short" })
    .replace(".", "");
}

/**
 * Filtro de período e linha do tempo da execução.
 *
 * Cada marca da linha é um dia em que algum RDO lançou produção neste trecho.
 * Dias sem lançamento não aparecem: a linha mostra quando a obra andou, não um
 * calendário preenchido artificialmente.
 */
export function TrechoPeriodoFiltro({
  disponivel,
  valor,
  dias,
  diaSelecionado,
  onAlterarPeriodo,
  onSelecionarDia,
}: TrechoPeriodoFiltroProps) {
  const temPeriodo = Boolean(valor.de || valor.ate || diaSelecionado);
  const maiorExtensao = dias.reduce(
    (maior, dia) => Math.max(maior, dia.extensaoM),
    0,
  );

  if (!disponivel.de) {
    return (
      <p className="trecho-periodo-vazio">
        Nenhuma data de execução registrada ainda. A linha do tempo aparece
        quando o primeiro RDO com produção for lançado nesta obra.
      </p>
    );
  }

  return (
    <div className="trecho-periodo">
      <div className="trecho-periodo-campos">
        <label>
          <span>De</span>
          <input
            type="date"
            value={valor.de ?? ""}
            min={disponivel.de ?? undefined}
            max={valor.ate ?? disponivel.ate ?? undefined}
            onChange={(event) =>
              onAlterarPeriodo({ ...valor, de: event.target.value || null })
            }
          />
        </label>
        <label>
          <span>Até</span>
          <input
            type="date"
            value={valor.ate ?? ""}
            min={valor.de ?? disponivel.de ?? undefined}
            max={disponivel.ate ?? undefined}
            onChange={(event) =>
              onAlterarPeriodo({ ...valor, ate: event.target.value || null })
            }
          />
        </label>
        <button
          type="button"
          className="trecho-periodo-limpar"
          disabled={!temPeriodo}
          onClick={() => {
            onSelecionarDia(null);
            onAlterarPeriodo({ de: null, ate: null });
          }}
        >
          Todo o período
        </button>
        <span className="trecho-periodo-extensao">
          Execução registrada de {formatarData(disponivel.de)} a{" "}
          {formatarData(disponivel.ate)}
        </span>
      </div>

      {dias.length > 0 ? (
        <ol
          className="trecho-linha-tempo"
          aria-label="Dias com produção lançada"
        >
          {dias.map((dia) => {
            const proporcao =
              maiorExtensao > 0 ? (dia.extensaoM / maiorExtensao) * 100 : 0;
            const ativo = dia.data === diaSelecionado;
            return (
              <li key={dia.data}>
                <button
                  type="button"
                  className={
                    ativo
                      ? "trecho-dia trecho-dia--ativo"
                      : "trecho-dia"
                  }
                  aria-pressed={ativo}
                  onClick={() => onSelecionarDia(ativo ? null : dia.data)}
                  title={`${dia.totalRdos} RDO(s) · ${numero(
                    dia.extensaoM,
                  )} m executados`}
                >
                  <span className="trecho-dia-barra" aria-hidden="true">
                    <span style={{ height: `${Math.max(proporcao, 6)}%` }} />
                  </span>
                  <strong>{formatarData(dia.data).slice(0, 5)}</strong>
                  <small>{diaDaSemana(dia.data)}</small>
                </button>
              </li>
            );
          })}
        </ol>
      ) : (
        <p className="trecho-periodo-vazio">
          Nenhuma produção lançada dentro do período escolhido.
        </p>
      )}
    </div>
  );
}
