import { useEffect, useMemo, useState } from "react";

import { SYNC_COMPLETED_EVENT } from "../../../lib/sync/syncEvents";
import { resolveFinanceCapabilities } from "../../financeiro/financeCapabilitiesResolver";
import {
  loadRevenueTraceSnapshot,
  type RevenueTraceSnapshot,
} from "../../financeiro/revenueTraceCacheRepository";
import { formatarData, type Periodo } from "./trechoGeometry";

interface TrechoReceitaSectionProps {
  obraId: string;
  /**
   * Recorte aplicado ao trecho — o mesmo do esquemático, com o dia selecionado
   * já resolvido. Sem recorte, a receita mostra o dia atual de visualização.
   */
  periodo: Periodo;
  /** Dia atual, injetável para teste. Formato ISO `AAAA-MM-DD`. */
  hoje?: string;
}

/** Data local do aparelho: o dia de visualização de quem está em campo. */
function dataLocalIso(): string {
  const agora = new Date();
  const mes = String(agora.getMonth() + 1).padStart(2, "0");
  const dia = String(agora.getDate()).padStart(2, "0");
  return `${agora.getFullYear()}-${mes}-${dia}`;
}

/** Uma atividade agregada: mesmo serviço, mesma unidade, mesma moeda. */
interface AtividadeReceita {
  chave: string;
  servicoNome: string;
  unidade: string;
  moeda: string;
  quantidade: number;
  receita: number;
  totalRdos: number;
}

interface LeituraReceita {
  snapshot: RevenueTraceSnapshot;
  atividades: AtividadeReceita[];
  /** Totais por moeda: moedas diferentes nunca se somam. */
  totais: [string, number][];
}

type EstadoResolvido =
  | { fase: "sem-acesso" }
  | { fase: "pronto"; leitura: LeituraReceita }
  | { fase: "erro"; mensagem: string };

type Estado = EstadoResolvido | { fase: "carregando" };

function numeroDe(valor: number | string): number {
  const convertido = typeof valor === "number" ? valor : Number(valor);
  return Number.isFinite(convertido) ? convertido : 0;
}

function moeda(valor: number, codigo: string): string {
  try {
    return new Intl.NumberFormat("pt-BR", {
      style: "currency",
      currency: codigo,
    }).format(valor);
  } catch {
    // Código de moeda fora da ISO 4217 em dado legado: o valor aparece com o
    // código cru em vez de sumir ou de fingir ser real brasileiro.
    return `${codigo} ${new Intl.NumberFormat("pt-BR", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(valor)}`;
  }
}

function agregarPorAtividade(
  snapshot: RevenueTraceSnapshot,
): Pick<LeituraReceita, "atividades" | "totais"> {
  const porAtividade = new Map<
    string,
    AtividadeReceita & { rdos: Set<string> }
  >();
  const porMoeda = new Map<string, number>();

  for (const row of snapshot.response.rows) {
    const chave = `${row.serviceId}::${row.unit}::${row.currency}`;
    const receita = numeroDe(row.revenue);
    const atual = porAtividade.get(chave);
    if (atual) {
      atual.quantidade += numeroDe(row.quantity);
      atual.receita += receita;
      atual.rdos.add(row.rdoId);
    } else {
      porAtividade.set(chave, {
        chave,
        servicoNome: row.serviceName,
        unidade: row.unit,
        moeda: row.currency,
        quantidade: numeroDe(row.quantity),
        receita,
        totalRdos: 0,
        rdos: new Set([row.rdoId]),
      });
    }
    porMoeda.set(row.currency, (porMoeda.get(row.currency) ?? 0) + receita);
  }

  const atividades = [...porAtividade.values()]
    .map(({ rdos, ...atividade }) => ({
      ...atividade,
      totalRdos: rdos.size,
    }))
    .sort((a, b) => b.receita - a.receita);

  return { atividades, totais: [...porMoeda.entries()] };
}

/**
 * Verificação do PDOR sobre o período do trecho.
 *
 * Cada valor é reconstruído de evidência aceita — o serviço executado no RDO
 * multiplicado pelo preço contratado na versão vigente — que é a mesma trilha
 * que alimenta o PDOR. Nada aqui é previsão: é a receita que a execução
 * registrada já sustenta, no recorte de tempo que o esquemático está
 * mostrando. Sem recorte, o dia de hoje.
 *
 * A receita é dado financeiro: quem não tem FINANCEIRO_VISUALIZAR na obra vê
 * o aviso de acesso, e nenhuma consulta de valores é feita.
 */
export function TrechoReceitaSection({
  obraId,
  periodo,
  hoje,
}: TrechoReceitaSectionProps) {
  // O resultado carrega a chave da consulta que o produziu: enquanto a chave
  // atual for outra — período mudou, sincronização rodou — a fase é
  // "carregando" por derivação, sem nenhuma escrita síncrona de estado.
  const [resultado, setResultado] = useState<{
    chave: string;
    estado: EstadoResolvido;
  } | null>(null);
  const [ciclo, setCiclo] = useState(0);

  useEffect(() => {
    function aoSincronizar() {
      setCiclo((anterior) => anterior + 1);
    }
    window.addEventListener(SYNC_COMPLETED_EVENT, aoSincronizar);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, aoSincronizar);
    };
  }, []);

  // O período efetivo da receita segue o recorte do trecho; um extremo solto
  // fecha no próprio dia, e nenhum recorte significa o dia atual.
  const diaAtual = hoje ?? dataLocalIso();
  const de = periodo.de ?? periodo.ate ?? diaAtual;
  const ate = periodo.ate ?? periodo.de ?? diaAtual;
  const padraoHoje = !periodo.de && !periodo.ate;

  const chave = `${obraId}|${de}|${ate}|${ciclo}`;

  useEffect(() => {
    let cancelado = false;

    async function carregar(): Promise<EstadoResolvido> {
      const capacidades = await resolveFinanceCapabilities(obraId);
      if (!capacidades.permissoes.includes("FINANCEIRO_VISUALIZAR")) {
        return { fase: "sem-acesso" };
      }
      const snapshot = await loadRevenueTraceSnapshot({
        obraId,
        from: de,
        to: ate,
      });
      return {
        fase: "pronto",
        leitura: { snapshot, ...agregarPorAtividade(snapshot) },
      };
    }

    carregar()
      .then((estado) => {
        if (!cancelado) setResultado({ chave, estado });
      })
      .catch((motivo: unknown) => {
        if (cancelado) return;
        setResultado({
          chave,
          estado: {
            fase: "erro",
            mensagem:
              motivo instanceof Error
                ? motivo.message
                : "Receita do período indisponível.",
          },
        });
      });

    return () => {
      cancelado = true;
    };
  }, [chave, obraId, de, ate]);

  const estado: Estado =
    resultado && resultado.chave === chave
      ? resultado.estado
      : { fase: "carregando" };

  const rotuloPeriodo = padraoHoje
    ? `hoje · ${formatarData(diaAtual)}`
    : de === ate
      ? formatarData(de)
      : `${formatarData(de)} – ${formatarData(ate)}`;

  const leitura = estado.fase === "pronto" ? estado.leitura : null;
  const maiorReceita = useMemo(
    () =>
      leitura
        ? leitura.atividades.reduce(
            (maior, atividade) => Math.max(maior, atividade.receita),
            0,
          )
        : 0,
    [leitura],
  );

  if (estado.fase === "sem-acesso") {
    return (
      <section className="trecho-receita" aria-labelledby="trecho-receita-title">
        <header className="trecho-receita-header">
          <div>
            <p className="eyebrow">Verificação PDOR</p>
            <h3 id="trecho-receita-title">Receita evidenciada</h3>
          </div>
        </header>
        <p className="trecho-receita-vazio">
          Os valores desta obra são visíveis para quem tem acesso financeiro
          (FINANCEIRO_VISUALIZAR). O acompanhamento físico do trecho acima
          continua completo.
        </p>
      </section>
    );
  }

  return (
    <section className="trecho-receita" aria-labelledby="trecho-receita-title">
      <header className="trecho-receita-header">
        <div>
          <p className="eyebrow">Verificação PDOR</p>
          <h3 id="trecho-receita-title">Receita evidenciada</h3>
          <span className="trecho-receita-meta">
            {rotuloPeriodo}
            {leitura
              ? ` · ${leitura.snapshot.response.evidenceCount} evidência(s) aceita(s)`
              : ""}
          </span>
        </div>
        {leitura && leitura.totais.length > 0 ? (
          <div className="trecho-receita-total">
            <span>Total do período</span>
            {leitura.totais.map(([codigo, valor]) => (
              <strong key={codigo}>{moeda(valor, codigo)}</strong>
            ))}
          </div>
        ) : null}
      </header>

      {estado.fase === "carregando" ? (
        <p className="trecho-receita-vazio">
          Verificando as evidências de receita do período…
        </p>
      ) : null}

      {estado.fase === "erro" ? (
        <p className="trecho-receita-vazio">{estado.mensagem}</p>
      ) : null}

      {leitura ? (
        leitura.atividades.length > 0 ? (
          <ul className="trecho-receita-atividades">
            {leitura.atividades.map((atividade) => (
              <li key={atividade.chave}>
                <div className="trecho-receita-atividade-texto">
                  <strong>{atividade.servicoNome}</strong>
                  <span>
                    {new Intl.NumberFormat("pt-BR", {
                      maximumFractionDigits: 2,
                    }).format(atividade.quantidade)}{" "}
                    {atividade.unidade} · {atividade.totalRdos} RDO(s)
                  </span>
                </div>
                <div className="trecho-receita-atividade-valor">
                  <span
                    className="trecho-receita-barra"
                    aria-hidden="true"
                    style={{
                      width: `${
                        maiorReceita > 0
                          ? Math.max(
                              (atividade.receita / maiorReceita) * 100,
                              2,
                            )
                          : 0
                      }%`,
                    }}
                  />
                  <strong>{moeda(atividade.receita, atividade.moeda)}</strong>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="trecho-receita-vazio">
            Nenhuma receita evidenciada
            {padraoHoje ? " hoje" : " neste período"}. O valor aparece quando o
            serviço executado no RDO é aceito com preço contratado vigente.
          </p>
        )
      ) : null}

      {leitura ? (
        <footer className="trecho-receita-notas">
          <small>
            Cada valor sai de evidência aceita: serviço executado no RDO ×
            preço contratado na versão vigente — a mesma trilha que alimenta o
            PDOR.
          </small>
          {leitura.snapshot.response.coverage === "PARTIAL" ? (
            <small className="trecho-receita-nota-atencao">
              Cobertura parcial: há mais evidências no período do que esta
              página carregou.
            </small>
          ) : null}
          {leitura.snapshot.mode === "OFFLINE_CACHE" ? (
            <small>
              Dados do dispositivo, confirmados pelo servidor em{" "}
              {new Date(leitura.snapshot.fetchedAt).toLocaleDateString(
                "pt-BR",
              )}
              .
            </small>
          ) : null}
        </footer>
      ) : null}
    </section>
  );
}
