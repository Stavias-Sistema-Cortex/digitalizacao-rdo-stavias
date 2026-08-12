import type { CSSProperties } from "react";
import {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import type { ObraLocalRecord } from "../../../lib/db/db.types";
import { SYNC_COMPLETED_EVENT } from "../../../lib/sync/syncEvents";
import { filterObrasByChip } from "../../home/homeFilters";

import { lerApontamentosDoAparelho } from "./apontamentosDoAparelho";
import type { LeituraDeApontamentos } from "./apontamentosDoAparelho";
import { coresPorObra } from "./coresDoRateio";
import {
  baixarCsvDoRateio,
  montarCsvDoRateio,
  nomeDoArquivoDoRateio,
} from "./exportarRateio";
import { encarregadosDoRateio, filtrarRateio } from "./filtrosDoRateio";
import { mensagemDeRateioVazio } from "./mensagemDeRateioVazio";
import { buscarApontamentosDoServidor } from "./rateioApi";
import {
  apurarRateio,
  diasDoPeriodo,
  mesDe,
  type RateioDeColaborador,
} from "./rateioDeMaoDeObra";

import "./RateioMaoDeObraPanel.css";

/**
 * O rateio de mão de obra entre as obras, mês a mês.
 *
 * <p>Ninguém digita esta tela. Ela lê os RDOs — que já dizem quem trabalhou em
 * que obra, em que dia, sob qual frente — e monta o quadro que antes era
 * mantido à mão numa planilha. Cada novo RDO enviado muda o quadro sozinho.
 *
 * <p>Funciona sem rede porque a conta é feita aqui, sobre o que a sincronização
 * já trouxe. Com rede, o servidor entrega o período inteiro e a tela troca o
 * retrato pelo mais completo — dizendo qual dos dois está mostrando.
 */

const NOME_DO_MES = new Intl.DateTimeFormat("pt-BR", {
  month: "long",
  year: "numeric",
  timeZone: "UTC",
});

const PERCENTUAL = new Intl.NumberFormat("pt-BR", {
  style: "percent",
  maximumFractionDigits: 1,
});

const DIAS_DA_SEMANA = ["D", "S", "T", "Q", "Q", "S", "S"] as const;

type OrigemDoRetrato = "APARELHO" | "SERVIDOR";

interface RateioMaoDeObraPanelProps {
  obras: readonly ObraLocalRecord[];
  /** Mês inicial (`AAAA-MM`); serve para o teste fixar o calendário. */
  mesInicial?: string;
}

function mesCorrente(): string {
  const agora = new Date();
  return `${agora.getFullYear()}-${String(agora.getMonth() + 1).padStart(2, "0")}`;
}

function deslocarMes(mes: string, passos: number): string {
  const [ano, numero] = mes.split("-").map((parte) => Number(parte));
  const referencia = new Date(Date.UTC(ano, numero - 1 + passos, 1));
  return `${referencia.getUTCFullYear()}-${String(
    referencia.getUTCMonth() + 1,
  ).padStart(2, "0")}`;
}

function corDaObra(cor: number | undefined): CSSProperties {
  return { "--cor-a": `var(--rateio-cor-${cor ?? 1})` } as CSSProperties;
}

function corDoDia(cores: readonly number[]): CSSProperties {
  const estilo: Record<string, string> = {
    "--cor-a": `var(--rateio-cor-${cores[0] ?? 1})`,
  };
  if (cores.length > 1) {
    estilo["--cor-b"] = `var(--rateio-cor-${cores[1]})`;
  }
  return estilo as CSSProperties;
}

export function RateioMaoDeObraPanel({
  obras,
  mesInicial,
}: RateioMaoDeObraPanelProps) {
  const [mes, setMes] = useState<string>(mesInicial ?? mesCorrente());
  const [busca, setBusca] = useState("");
  const [frente, setFrente] = useState("");
  const [somenteEmExecucao, setSomenteEmExecucao] = useState(true);
  const [leitura, setLeitura] = useState<LeituraDeApontamentos | null>(null);
  const [origem, setOrigem] = useState<OrigemDoRetrato>("APARELHO");
  const [carregando, setCarregando] = useState(true);
  const [erro, setErro] = useState("");
  const geracaoRef = useRef(0);

  const periodo = useMemo(() => mesDe(`${mes}-01`), [mes]);
  const dias = useMemo(
    () => diasDoPeriodo(periodo.inicio, periodo.fim),
    [periodo.inicio, periodo.fim],
  );

  const carregar = useCallback(async () => {
    const geracao = (geracaoRef.current += 1);
    setCarregando(true);
    setErro("");
    try {
      // O aparelho responde primeiro porque responde na hora: a tela abre com
      // o que já existe aqui e melhora sozinha se houver rede.
      const local = await lerApontamentosDoAparelho({
        inicio: periodo.inicio,
        fim: periodo.fim,
      });
      if (geracao !== geracaoRef.current) return;
      setLeitura(local);
      setOrigem("APARELHO");

      if (typeof navigator !== "undefined" && !navigator.onLine) return;
      try {
        const remoto = await buscarApontamentosDoServidor(
          periodo.inicio,
          periodo.fim,
        );
        if (geracao !== geracaoRef.current) return;
        setLeitura(remoto);
        setOrigem("SERVIDOR");
      } catch {
        // Sem rede, ou servidor calado: o retrato do aparelho continua valendo
        // e a faixa de proveniência já diz de onde ele veio.
      }
    } catch (falha: unknown) {
      if (geracao !== geracaoRef.current) return;
      setErro(
        falha instanceof Error
          ? falha.message
          : "Falha ao ler os RDOs deste aparelho.",
      );
    } finally {
      if (geracao === geracaoRef.current) setCarregando(false);
    }
  }, [periodo.inicio, periodo.fim]);

  useEffect(() => {
    // O disparo sai do próprio efeito para a fila seguinte: a leitura muda
    // estado, e mudá-lo dentro do efeito prenderia a renderização a ela.
    const agendado = window.setTimeout(() => {
      void carregar();
    }, 0);
    return () => window.clearTimeout(agendado);
  }, [carregar]);

  useEffect(() => {
    const aoSincronizar = () => {
      void carregar();
    };
    window.addEventListener(SYNC_COMPLETED_EVENT, aoSincronizar);
    window.addEventListener("online", aoSincronizar);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, aoSincronizar);
      window.removeEventListener("online", aoSincronizar);
    };
  }, [carregar]);

  const nomePorObra = useMemo(() => {
    const nomes = new Map<string, string>();
    for (const obra of obras) nomes.set(obra.id, obra.nome);
    return nomes;
  }, [obras]);

  const obrasEmExecucao = useMemo(
    () =>
      new Set(
        filterObrasByChip([...obras], "EM_EXECUCAO").map((obra) => obra.id),
      ),
    [obras],
  );

  /*
   * A conta é sempre sobre o período inteiro, com todas as obras. O recorte da
   * tela entra depois, em `filtrarRateio` — é o que impede a fatia de uma
   * pessoa de ser recalculada só entre as obras visíveis e virar um número
   * bonito e falso.
   */
  const rateioCompleto = useMemo(
    () => apurarRateio(leitura?.apontamentos ?? []),
    [leitura],
  );

  const obrasVisiveis = useMemo(
    () =>
      somenteEmExecucao
        ? rateioCompleto.obraIds.filter((obraId) =>
            obrasEmExecucao.has(obraId),
          )
        : [],
    [somenteEmExecucao, rateioCompleto.obraIds, obrasEmExecucao],
  );

  const rateio = useMemo(
    () =>
      filtrarRateio(rateioCompleto, {
        obraIds: obrasVisiveis,
        busca,
        encarregado: frente,
      }),
    [rateioCompleto, obrasVisiveis, busca, frente],
  );

  const frentes = useMemo(
    () => encarregadosDoRateio(rateioCompleto),
    [rateioCompleto],
  );

  const cores = useMemo(
    () => coresPorObra(rateioCompleto.obraIds),
    [rateioCompleto.obraIds],
  );

  const exportar = useCallback(() => {
    const conteudo = montarCsvDoRateio(
      rateio,
      dias,
      rateio.obraIds.map((obraId) => ({
        id: obraId,
        nome: nomePorObra.get(obraId) ?? obraId,
      })),
    );
    baixarCsvDoRateio(
      conteudo,
      nomeDoArquivoDoRateio(periodo.inicio, periodo.fim),
    );
  }, [rateio, dias, nomePorObra, periodo.inicio, periodo.fim]);

  const rotuloDoMes = useMemo(() => {
    const nome = NOME_DO_MES.format(new Date(`${periodo.inicio}T12:00:00Z`));
    return nome.charAt(0).toUpperCase() + nome.slice(1);
  }, [periodo.inicio]);

  const totalDeDias = rateio.diasApontadosNoTotal;

  return (
    <section className="rateio" aria-label="Rateio de mão de obra">
      <header className="rateio-controles">
        <div className="rateio-mes" role="group" aria-label="Mês do rateio">
          <button
            type="button"
            className="rateio-mes-passo"
            onClick={() => setMes(deslocarMes(mes, -1))}
            aria-label="Mês anterior"
          >
            ‹
          </button>
          <strong className="rateio-mes-rotulo">{rotuloDoMes}</strong>
          <button
            type="button"
            className="rateio-mes-passo"
            onClick={() => setMes(deslocarMes(mes, 1))}
            aria-label="Próximo mês"
          >
            ›
          </button>
        </div>

        <label className="rateio-campo">
          <span className="sr-only">Procurar pessoa ou função</span>
          <input
            type="search"
            value={busca}
            onChange={(evento) => setBusca(evento.target.value)}
            placeholder="Procurar pessoa ou função"
          />
        </label>

        <label className="rateio-campo">
          <span className="sr-only">Frente</span>
          <select
            value={frente}
            onChange={(evento) => setFrente(evento.target.value)}
          >
            <option value="">Todas as frentes</option>
            {frentes.map((nome) => (
              <option key={nome} value={nome}>
                {nome}
              </option>
            ))}
          </select>
        </label>

        <label className="rateio-alternador">
          <input
            type="checkbox"
            checked={somenteEmExecucao}
            onChange={(evento) => setSomenteEmExecucao(evento.target.checked)}
          />
          <span>Só obras em execução</span>
        </label>

        <button
          type="button"
          className="rateio-exportar"
          onClick={exportar}
          disabled={rateio.colaboradores.length === 0}
        >
          Exportar planilha
        </button>
      </header>

      <FaixaDeProveniencia
        origem={origem}
        leitura={leitura}
        carregando={carregando}
        erro={erro}
      />

      <div className="rateio-resumo">
        <article className="rateio-cartao">
          <span className="rateio-cartao-rotulo">Pessoas</span>
          <strong className="rateio-cartao-valor">
            {rateio.colaboradores.length}
          </strong>
        </article>
        <article className="rateio-cartao">
          <span className="rateio-cartao-rotulo">Dias apontados</span>
          <strong className="rateio-cartao-valor">{totalDeDias}</strong>
        </article>
        <article className="rateio-cartao">
          <span className="rateio-cartao-rotulo">Obras</span>
          <strong className="rateio-cartao-valor">
            {rateio.obraIds.length}
          </strong>
        </article>
        <article className="rateio-cartao">
          <span className="rateio-cartao-rotulo">Frentes</span>
          <strong className="rateio-cartao-valor">
            {rateio.totaisPorEncarregado.length}
          </strong>
        </article>
      </div>

      {rateio.obraIds.length > 0 ? (
        <section className="rateio-distribuicao" aria-label="Divisão do período">
          <div className="rateio-barra">
            {rateio.obraIds.map((obraId) => {
              const total = rateio.totaisPorObra.get(obraId);
              const fatia =
                totalDeDias > 0 ? (total?.diasApontados ?? 0) / totalDeDias : 0;
              if (fatia <= 0) return null;
              return (
                <span
                  key={obraId}
                  className="rateio-barra-fatia"
                  style={{
                    ...corDaObra(cores.get(obraId)),
                    width: `${fatia * 100}%`,
                  }}
                  title={`${nomePorObra.get(obraId) ?? obraId}: ${PERCENTUAL.format(fatia)}`}
                />
              );
            })}
          </div>
          <ul className="rateio-legenda">
            {rateio.obraIds.map((obraId) => {
              const total = rateio.totaisPorObra.get(obraId);
              const fatia =
                totalDeDias > 0 ? (total?.diasApontados ?? 0) / totalDeDias : 0;
              return (
                <li key={obraId} className="rateio-legenda-item">
                  <span
                    className="rateio-marca"
                    style={corDaObra(cores.get(obraId))}
                    aria-hidden="true"
                  />
                  <span className="rateio-legenda-nome">
                    {nomePorObra.get(obraId) ?? obraId}
                  </span>
                  <span className="rateio-legenda-numero">
                    {PERCENTUAL.format(fatia)}
                  </span>
                  <span className="rateio-legenda-detalhe">
                    {total?.pessoas ?? 0} pessoas
                  </span>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {rateio.colaboradores.length === 0 ? (
        <p className="rateio-vazio" role="status">
          {mensagemDeRateioVazio(leitura, carregando)}
        </p>
      ) : (
        <MatrizDoRateio
          colaboradores={rateio.colaboradores}
          dias={dias}
          obraIds={rateio.obraIds}
          cores={cores}
          nomePorObra={nomePorObra}
        />
      )}
    </section>
  );
}

function FaixaDeProveniencia({
  origem,
  leitura,
  carregando,
  erro,
}: {
  origem: OrigemDoRetrato;
  leitura: LeituraDeApontamentos | null;
  carregando: boolean;
  erro: string;
}) {
  if (erro) {
    return (
      <p className="rateio-proveniencia is-erro" role="alert">
        {erro}
      </p>
    );
  }
  const pendentes = leitura?.rdosSemConteudo ?? 0;
  const classe =
    origem === "SERVIDOR"
      ? "rateio-proveniencia is-servidor"
      : "rateio-proveniencia is-aparelho";
  return (
    <p className={classe} role="status">
      {origem === "SERVIDOR"
        ? "Retrato do servidor: todos os RDOs do período."
        : "Retrato deste aparelho: os RDOs que já sincronizaram."}
      {pendentes > 0
        ? ` ${pendentes} ${pendentes === 1 ? "RDO ainda não desceu" : "RDOs ainda não desceram"} inteiro${pendentes === 1 ? "" : "s"}.`
        : ""}
      {carregando ? " Atualizando…" : ""}
    </p>
  );
}

function MatrizDoRateio({
  colaboradores,
  dias,
  obraIds,
  cores,
  nomePorObra,
}: {
  colaboradores: readonly RateioDeColaborador[];
  dias: readonly string[];
  obraIds: readonly string[];
  cores: ReadonlyMap<string, number>;
  nomePorObra: ReadonlyMap<string, string>;
}) {
  // As linhas já chegam ordenadas por frente; a faixa é só a marca visual de
  // onde um bloco termina e outro começa — por isso basta comparar com a linha
  // anterior, sem guardar estado entre renderizações.
  return (
    <div className="rateio-matriz-wrap">
      <table className="rateio-matriz">
        <thead>
          <tr>
            <th className="rateio-col-nome" scope="col">
              Mão de obra
            </th>
            <th className="rateio-col-funcao" scope="col">
              Função
            </th>
            {dias.map((dia) => {
              const data = new Date(`${dia}T12:00:00Z`);
              const semana = data.getUTCDay();
              return (
                <th
                  key={dia}
                  scope="col"
                  className={
                    semana === 0 || semana === 6
                      ? "rateio-col-dia is-descanso"
                      : "rateio-col-dia"
                  }
                >
                  <span className="rateio-dia-numero">{dia.slice(8)}</span>
                  <span className="rateio-dia-semana">
                    {DIAS_DA_SEMANA[semana]}
                  </span>
                </th>
              );
            })}
            {obraIds.map((obraId) => (
              <th key={obraId} scope="col" className="rateio-col-obra">
                <span
                  className="rateio-marca"
                  style={corDaObra(cores.get(obraId))}
                  aria-hidden="true"
                />
                {nomePorObra.get(obraId) ?? obraId}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {colaboradores.map((colaborador, indice) => {
            const abreFrente =
              indice === 0 ||
              colaboradores[indice - 1].encarregado !==
                colaborador.encarregado;
            const colunas = 2 + dias.length + obraIds.length;
            return (
              <Fragment key={colaborador.chave}>
                {abreFrente ? (
                  <tr className="rateio-frente">
                    <th scope="rowgroup" colSpan={colunas}>
                      {colaborador.encarregado || "Sem frente declarada"}
                    </th>
                  </tr>
                ) : null}
                <tr>
                  <th scope="row" className="rateio-col-nome">
                    {colaborador.nome}
                  </th>
                  <td className="rateio-col-funcao">{colaborador.funcao}</td>
                  {dias.map((dia) => {
                    const registro = colaborador.dias.get(dia);
                    const visiveis = (registro?.obraIds ?? []).filter((obraId) =>
                      obraIds.includes(obraId),
                    );
                    if (visiveis.length === 0) {
                      return (
                        <td
                          key={dia}
                          className="rateio-celula is-vazia"
                          aria-label="Sem apontamento"
                        />
                      );
                    }
                    const nomes = visiveis
                      .map((obraId) => nomePorObra.get(obraId) ?? obraId)
                      .join(" e ");
                    return (
                      <td
                        key={dia}
                        className={
                          visiveis.length > 1
                            ? "rateio-celula is-dividida"
                            : "rateio-celula"
                        }
                        style={corDoDia(
                          visiveis.map((obraId) => cores.get(obraId) ?? 1),
                        )}
                        title={`${dia.slice(8)}: ${nomes}`}
                      >
                        <span className="sr-only">{nomes}</span>
                      </td>
                    );
                  })}
                  {obraIds.map((obraId) => {
                    const fatia = colaborador.fracaoPorObra.get(obraId) ?? 0;
                    return (
                      <td
                        key={obraId}
                        className={
                          fatia > 0
                            ? "rateio-col-obra is-number"
                            : "rateio-col-obra is-number is-zero"
                        }
                      >
                        {fatia > 0 ? PERCENTUAL.format(fatia) : "—"}
                      </td>
                    );
                  })}
                </tr>
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
