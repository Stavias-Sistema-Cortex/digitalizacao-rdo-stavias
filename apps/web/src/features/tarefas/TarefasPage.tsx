import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";

import { CortexShell } from "../../components/shell/CortexShell";
import { OperationalWorkspace } from "../../components/workspace/OperationalWorkspace";
import { getSession, isAlfa } from "../auth/authSession";
import type { TeamDto } from "../equipes/teamApi";
import { apagarEquipeDefinitivamente } from "../equipes/apagarEquipe";
import {
  listLocalTeams,
} from "../equipes/teamLocalRepository";
import {
  abasDeEquipe,
  chaveDaEquipe,
  filtrarAbas,
  type EquipeNaAba,
  type FiltroDeEquipe,
} from "./abasDeEquipe";
import {
  listObrasLocais,
} from "../../lib/db/obraLocalRepository";
import { filterOperationalObras } from "../../lib/db/obraSelectors";
import { listLocalRdosByObra } from "../../lib/db/rdoRepository";
import {
  createTarefa,
  deleteTarefa,
  listTarefasByObra,
  setTarefaConclusao,
  type TarefaAtor,
} from "../../lib/db/tarefaRepository";
import type {
  ColaboradorLocalRecord,
  LocalRdoRecord,
  ObraLocalRecord,
  TarefaPrioridade,
  TarefaRecord,
} from "../../lib/db/db.types";
import {
  hidratarColaboradoresAcademy,
  listarColaboradoresConhecidos,
} from "./colaboradoresAcademy";
import {
  buscarPorNome,
  reconhecerNomeExato,
} from "./nomeMatcher";
import {
  hydrateObrasRelacionadas,
} from "../home/homeHydration";
import {
  colaboradorStorageKey,
  getLastAccessedObraId,
  setLastAccessedObraId,
} from "../home/lastAccessedObra";
import type { ChartPeriod } from "../home/progressSeries";
import {
  equipesDaObra,
  responsaveisSugeridos,
} from "./equipesDaObra";
import {
  buildTarefaSeries,
  eficienciaConclusao,
  filterTarefaSeriesByPeriod,
} from "./tarefaEficiencia";
import { TarefaEficienciaChart } from "./TarefaEficienciaChart";
import "./TarefasPage.css";

const PRIORIDADES: {
  value: TarefaPrioridade;
  label: string;
  hint: string;
}[] = [
  { value: 1, label: "P1", hint: "Alta" },
  { value: 2, label: "P2", hint: "Média" },
  { value: 3, label: "P3", hint: "Baixa" },
];

type StatusFilter = "TODAS" | "PENDENTES" | "CONCLUIDAS";

type PrioridadeFilter = "TODAS" | TarefaPrioridade;

function equipeKey(value: string): string {
  return value.trim().toLocaleLowerCase("pt-BR");
}

function formatDate(iso: string | null): string {
  if (!iso) {
    return "";
  }

  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
  }).format(date);
}

function formatMonthYear(month: string): string {
  return `${month.slice(5)}/${month.slice(0, 4)}`;
}

function prioridadeInfo(value: TarefaPrioridade) {
  return (
    PRIORIDADES.find(
      (option) => option.value === value,
    ) ?? PRIORIDADES[1]
  );
}

/** Bandeira colorida por grau: P1 azul, P2 amarela, P3 vermelha. */
/*
 * Traço, não desenho.
 *
 * Antes era o emoji 🗄, que cada sistema desenha do seu jeito, chega colorido
 * numa barra que não tem cor nenhuma e ignora o peso de linha do resto da
 * interface. Como SVG de contorno o ícone herda `currentColor` e acompanha o
 * estado do botão.
 */


function BandeiraPrioridade({
  prioridade,
}: {
  prioridade: TarefaPrioridade;
}) {
  return (
    <svg
      viewBox="0 0 16 16"
      className={`tarefa-bandeira tarefa-bandeira--${prioridade}`}
      aria-hidden="true"
      focusable="false"
    >
      <path
        d="M3.5 1.8v12.4"
        stroke="#5b6570"
        strokeWidth="1.5"
        strokeLinecap="round"
        fill="none"
      />
      <path
        d="M4.4 2.4h8l-2.1 2.9 2.1 2.9h-8z"
        fill="currentColor"
      />
    </svg>
  );
}

interface ResponsavelReconhecido {
  id: string;
  nome: string;
}

export function TarefasPage() {
  const session = getSession();
  const storageKey = useMemo(
    () => colaboradorStorageKey(getSession()),
    [],
  );
  const [obras, setObras] = useState<ObraLocalRecord[]>(
    [],
  );
  const [focusedObraId, setFocusedObraIdState] = useState<
    string | null
  >(() => getLastAccessedObraId(storageKey));
  const [detalhes, setDetalhes] = useState<{
    obraId: string;
    rdos: LocalRdoRecord[];
    tarefas: TarefaRecord[];
  } | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [reloadTick, setReloadTick] = useState(0);

  // Estado por obra: trocar de aba não descarta o que o
  // usuário fez na outra obra.
  const [equipesManuaisPorObra] =
    useState<Record<string, string[]>>({});
  const [equipeEscolhidaPorObra] =
    useState<Record<string, string>>({});

  // As equipes cadastradas vêm do cache local: a aba abre e arquiva sem rede.
  const [equipesCadastradas, setEquipesCadastradas] =
    useState<TeamDto[]>([]);
  const filtroEquipe: FiltroDeEquipe = "TODAS";
  // Arquivar e desarquivar são atos de Alfa. O servidor impõe o mesmo em
  // EquipeService; aqui só se evita oferecer um botão que voltaria 403.
  /*
   * Apagar equipe é ato de Alfa, e o servidor impõe isso em
   * EquipeDeletionService. Aqui só se evita oferecer um botão que voltaria 403.
   */
  const podeApagarEquipe = isAlfa(session);
  const [equipeEmExclusao, setEquipeEmExclusao] =
    useState<string | null>(null);
  const [erroDaExclusao, setErroDaExclusao] = useState("");
  const [confirmacaoDeExclusao, setConfirmacaoDeExclusao] =
    useState<EquipeNaAba | null>(null);

  const [statusFilter, setStatusFilter] =
    useState<StatusFilter>("TODAS");
  const [prioridadeFilter, setPrioridadeFilter] =
    useState<PrioridadeFilter>("TODAS");
  const [period, setPeriod] =
    useState<ChartPeriod>("12M");

  const [titulo, setTitulo] = useState("");
  const [observacoes, setObservacoes] = useState("");
  const [responsavel, setResponsavel] = useState("");
  const [responsavelReconhecido, setResponsavelReconhecido] =
    useState<ResponsavelReconhecido | null>(null);
  const [isSugestoesAbertas, setIsSugestoesAbertas] =
    useState(false);
  const [colaboradores, setColaboradores] = useState<
    ColaboradorLocalRecord[]
  >([]);
  const academySearchTimer = useRef<number | null>(null);
  const [prioridade, setPrioridade] =
    useState<TarefaPrioridade>(2);
  const [isSaving, setIsSaving] = useState(false);
  const [formError, setFormError] = useState("");

  // Cadastro do Academy: cache local primeiro (funciona
  // offline), depois tenta atualizar pela API.
  useEffect(() => {
    let cancelled = false;

    async function loadColaboradores() {
      const locais = await listarColaboradoresConhecidos();
      if (!cancelled && locais.length > 0) {
        setColaboradores(locais);
      }

      // Escopa por obra: o Beta carrega os colaboradores da obra a que tem
      // acesso (o catálogo global é administrativo). Sem obra selecionada,
      // usa apenas o cache local já existente.
      if (!focusedObraId) {
        return;
      }

      try {
        await hidratarColaboradoresAcademy("", focusedObraId);
      } catch {
        return;
      }

      const atualizados =
        await listarColaboradoresConhecidos();
      if (!cancelled) {
        setColaboradores(atualizados);
      }
    }

    void loadColaboradores();

    return () => {
      cancelled = true;
    };
  }, [focusedObraId]);

  const buscarNoAcademy = useCallback(
    (consulta: string) => {
      if (academySearchTimer.current !== null) {
        window.clearTimeout(academySearchTimer.current);
      }

      if (consulta.trim().length < 2) {
        return;
      }

      academySearchTimer.current = window.setTimeout(
        () => {
          void (async () => {
            try {
              await hidratarColaboradoresAcademy(
                consulta,
                focusedObraId ?? undefined,
              );
            } catch {
              return;
            }

            setColaboradores(
              await listarColaboradoresConhecidos(),
            );
          })();
        },
        300,
      );
    },
    [focusedObraId],
  );

  useEffect(
    () => () => {
      if (academySearchTimer.current !== null) {
        window.clearTimeout(academySearchTimer.current);
      }
    },
    [],
  );

  const reload = useCallback(() => {
    setReloadTick((tick) => tick + 1);
  }, []);

  const setFocusedObraId = useCallback(
    (obraId: string) => {
      setFocusedObraIdState(obraId);
      setLastAccessedObraId(storageKey, obraId);
    },
    [storageKey],
  );

  useEffect(() => {
    let cancelled = false;

    async function loadObras() {
      setIsLoading(true);

      try {
        await hydrateObrasRelacionadas();
      } catch {
        // Offline ou API indisponível: segue com o banco local.
      }

      const local = filterOperationalObras(
        await listObrasLocais(),
      );

      if (cancelled) {
        return;
      }

      local.sort((a, b) =>
        a.updatedAt < b.updatedAt ? 1 : -1,
      );
      setObras(local);

      setFocusedObraIdState((current) => {
        if (
          current &&
          local.some((obra) => obra.id === current)
        ) {
          return current;
        }
        return local[0]?.id ?? null;
      });

      setIsLoading(false);
    }

    void loadObras();

    return () => {
      cancelled = true;
    };
  }, [reloadTick]);

  useEffect(() => {
    if (
      !focusedObraId ||
      !obras.some((obra) => obra.id === focusedObraId)
    ) {
      return;
    }

    let cancelled = false;

    async function loadDetalhes(obraId: string) {
      setLoadError("");

      try {
        const [rdos, tarefasDaObra] = await Promise.all([
          listLocalRdosByObra(obraId),
          listTarefasByObra(obraId),
        ]);

        if (cancelled) {
          return;
        }

        setDetalhes({
          obraId,
          rdos,
          tarefas: tarefasDaObra,
        });
      } catch (error: unknown) {
        if (!cancelled) {
          setLoadError(
            error instanceof Error
              ? error.message
              : "Falha ao carregar as tarefas locais.",
          );
        }
      }
    }

    void loadDetalhes(focusedObraId);

    return () => {
      cancelled = true;
    };
  }, [focusedObraId, obras, reloadTick]);

  const isDetalhesLoading =
    Boolean(focusedObraId) &&
    detalhes?.obraId !== focusedObraId;

  const obraRdos = useMemo(
    () =>
      detalhes?.obraId === focusedObraId
        ? detalhes.rdos
        : [],
    [detalhes, focusedObraId],
  );

  const tarefas = useMemo(
    () =>
      detalhes?.obraId === focusedObraId
        ? detalhes.tarefas
        : [],
    [detalhes, focusedObraId],
  );

  const equipesManuais = useMemo(
    () =>
      focusedObraId
        ? (equipesManuaisPorObra[focusedObraId] ?? [])
        : [],
    [equipesManuaisPorObra, focusedObraId],
  );

  /*
   * As equipes cadastradas saem do cache local, não da API.
   *
   * A aba precisa listar, filtrar e arquivar sem rede — é a mesma tela que se
   * usa em campo. O store `teams` já é preenchido pela sincronização e pelas
   * próprias mutações otimistas desta página.
   */
  useEffect(() => {
    let cancelled = false;
    listLocalTeams()
      .then((teams) => {
        if (!cancelled) {
          setEquipesCadastradas(teams);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [reloadTick]);

  const abasDaObra = useMemo(() => {
    const derivadas = equipesDaObra(obraRdos, tarefas);
    const existentes = new Set(
      derivadas.map((equipe) => equipeKey(equipe)),
    );
    const manuais = equipesManuais.filter(
      (equipe) => !existentes.has(equipeKey(equipe)),
    );

    return abasDeEquipe(
      [...derivadas, ...manuais],
      equipesCadastradas.filter(
        (equipe) => equipe.obraPrincipalId === focusedObraId,
      ),
    );
  }, [
    obraRdos,
    tarefas,
    equipesManuais,
    equipesCadastradas,
    focusedObraId,
  ]);


  const abasVisiveis = useMemo(
    () => filtrarAbas(abasDaObra, filtroEquipe),
    [abasDaObra, filtroEquipe],
  );

  const equipes = useMemo(
    () => abasVisiveis.map((aba) => aba.nome),
    [abasVisiveis],
  );

  const selectedEquipe = useMemo(() => {
    const escolha = focusedObraId
      ? equipeEscolhidaPorObra[focusedObraId]
      : undefined;

    if (
      escolha &&
      equipes.some(
        (equipe) =>
          equipeKey(equipe) === equipeKey(escolha),
      )
    ) {
      return escolha;
    }

    return equipes[0] ?? "";
  }, [equipeEscolhidaPorObra, equipes, focusedObraId]);



  const responsaveis = useMemo(
    () => responsaveisSugeridos(obraRdos, tarefas),
    [obraRdos, tarefas],
  );

  const sugestoesResponsavel = useMemo(() => {
    if (!responsavel.trim() || responsavelReconhecido) {
      return [];
    }

    return buscarPorNome(
      colaboradores,
      (colaborador) => colaborador.nome,
      responsavel,
      6,
    );
  }, [colaboradores, responsavel, responsavelReconhecido]);

  const ator: TarefaAtor = {
    colaboradorId: session?.colaboradorId ?? null,
    nome: session?.nome ?? "Colaborador",
  };

  // Reconhece a pessoa digitada mesmo com capslock ou sem
  // acento: primeiro no Academy (com id), depois nos nomes
  // vistos nos RDOs (só canonicaliza a grafia).
  function resolverResponsavel(): {
    nome: string;
    id: string | null;
  } {
    if (responsavelReconhecido) {
      return {
        nome: responsavelReconhecido.nome,
        id: responsavelReconhecido.id,
      };
    }

    const doAcademy = reconhecerNomeExato(
      colaboradores,
      (colaborador) => colaborador.nome,
      responsavel,
    );

    if (doAcademy) {
      return { nome: doAcademy.nome, id: doAcademy.id };
    }

    const doRdo = reconhecerNomeExato(
      responsaveis,
      (nome) => nome,
      responsavel,
    );

    return {
      nome: doRdo ?? responsavel.trim(),
      id: null,
    };
  }

  /*
   * A tarefa é da obra.
   *
   * <p>Ela já nascia assim no banco — obra_id obrigatório —, e a equipe era só
   * um texto ao lado, usado para recortar a lista. Com a dinâmica de equipes
   * encerrada, esse recorte escondia tarefa de gente que precisava vê-la: quem
   * abria a obra via um pedaço do trabalho e tinha de adivinhar em que aba
   * estava o resto.
   */
  const tarefasDaObra = tarefas;

  const tarefasVisiveis = useMemo(() => {
    return tarefasDaObra
      .filter((tarefa) => {
        if (
          statusFilter === "PENDENTES" &&
          tarefa.concluida
        ) {
          return false;
        }
        if (
          statusFilter === "CONCLUIDAS" &&
          !tarefa.concluida
        ) {
          return false;
        }
        return (
          prioridadeFilter === "TODAS" ||
          tarefa.prioridade === prioridadeFilter
        );
      })
      .sort((a, b) => {
        if (a.concluida !== b.concluida) {
          return a.concluida ? 1 : -1;
        }
        if (a.prioridade !== b.prioridade) {
          return a.prioridade - b.prioridade;
        }
        return a.createdAt < b.createdAt ? 1 : -1;
      });
  }, [tarefasDaObra, statusFilter, prioridadeFilter]);

  const serie = useMemo(
    () =>
      filterTarefaSeriesByPeriod(
        buildTarefaSeries(tarefasDaObra),
        period,
      ),
    [tarefasDaObra, period],
  );

  const resumo = useMemo(
    () => eficienciaConclusao(tarefasDaObra),
    [tarefasDaObra],
  );

  const periodoLabel = useMemo(() => {
    if (serie.length === 0) {
      return "";
    }

    const first = formatMonthYear(serie[0].month);
    const last = formatMonthYear(
      serie[serie.length - 1].month,
    );

    return first === last
      ? `Período: ${first}`
      : `Período: ${first} – ${last}`;
  }, [serie]);


  /*
   * Apagar de vez, não arquivar. A equipe some do produto inteiro — é o que
   * sobra a fazer com a que ficou para trás quando a dinâmica de equipes
   * acabou. Nenhuma tarefa e nenhum RDO são tocados: a tarefa é da obra, e o
   * RDO guarda os nomes que gravou no dia.
   */
  async function handleApagarEquipe() {
    const aba = confirmacaoDeExclusao;
    if (!aba?.equipe || !podeApagarEquipe) {
      return;
    }
    setEquipeEmExclusao(aba.equipe.id);
    setErroDaExclusao("");
    try {
      await apagarEquipeDefinitivamente(aba.equipe);
      setEquipesCadastradas((current) =>
        current.filter((equipe) => equipe.id !== aba.equipe?.id),
      );
      setConfirmacaoDeExclusao(null);
    } catch (error) {
      setErroDaExclusao(
        error instanceof Error
          ? error.message
          : "Não foi possível apagar a equipe.",
      );
    } finally {
      setEquipeEmExclusao(null);
    }
  }


  async function handleCreateTarefa(
    event: FormEvent<HTMLFormElement>,
  ) {
    event.preventDefault();

    if (!focusedObraId) {
      setFormError("Escolha uma obra antes de criar a tarefa.");
      return;
    }

    if (!titulo.trim()) {
      setFormError("Dê um título para a tarefa.");
      return;
    }

    setIsSaving(true);
    setFormError("");

    try {
      const responsavelResolvido = resolverResponsavel();
      const criada = await createTarefa(
        {
          obraId: focusedObraId,
          // O campo continua existindo no registro e guarda a obra: ele é
          // obrigatório no banco e nenhuma tarefa antiga precisa ser tocada.
          equipe: focusedObra?.nome ?? "Obra",
          titulo,
          observacoes,
          criadaPor: ator.nome,
          criadaPorColaboradorId: ator.colaboradorId,
          responsavelEquipe: responsavelResolvido.nome,
          responsavelColaboradorId:
            responsavelResolvido.id,
          prioridade,
        },
        ator,
      );

      setDetalhes((current) =>
        current && current.obraId === criada.obraId
          ? {
              ...current,
              tarefas: [...current.tarefas, criada],
            }
          : current,
      );
      setTitulo("");
      setObservacoes("");
      setResponsavel("");
      setResponsavelReconhecido(null);
      setPrioridade(2);
    } catch (error: unknown) {
      setFormError(
        error instanceof Error
          ? error.message
          : "Não foi possível salvar a tarefa.",
      );
    } finally {
      setIsSaving(false);
    }
  }

  async function handleToggleConclusao(
    tarefa: TarefaRecord,
  ) {
    try {
      const updated = await setTarefaConclusao(
        tarefa.id,
        !tarefa.concluida,
        ator,
      );

      if (updated) {
        setDetalhes((current) =>
          current
            ? {
                ...current,
                tarefas: current.tarefas.map((item) =>
                  item.id === updated.id
                    ? updated
                    : item,
                ),
              }
            : current,
        );
      }
    } catch (error: unknown) {
      setLoadError(
        error instanceof Error
          ? error.message
          : "Não foi possível atualizar a tarefa.",
      );
    }
  }

  async function handleDeleteTarefa(
    tarefa: TarefaRecord,
  ) {
    const confirmed = window.confirm(
      `Excluir a tarefa "${tarefa.titulo}"?`,
    );

    if (!confirmed) {
      return;
    }

    try {
      await deleteTarefa(tarefa.id, ator);
      setDetalhes((current) =>
        current
          ? {
              ...current,
              tarefas: current.tarefas.filter(
                (item) => item.id !== tarefa.id,
              ),
            }
          : current,
      );
    } catch (error: unknown) {
      setLoadError(
        error instanceof Error
          ? error.message
          : "Não foi possível excluir a tarefa.",
      );
    }
  }

  const focusedObra =
    obras.find((obra) => obra.id === focusedObraId) ??
    null;

  return (
    <CortexShell
      active="tarefas"
      onRefresh={reload}
      isRefreshing={isLoading}
    >
      <OperationalWorkspace
        className="tarefas-page"
        eyebrow="Coordenação de campo"
        title="Tarefas"
        status={isLoading
          ? { code: "SYNCING", label: "Carregando tarefas locais" }
          : loadError
            ? { code: "CONFLICT", label: "Falha ao ler as tarefas" }
            : { code: "LOCAL", label: "Dados disponíveis neste dispositivo" }}
        actions={(
          <div className="home-uf-filter">
            <span>Filtrar por:</span>
            <select
              value={statusFilter}
              aria-label="Filtrar por status"
              onChange={(event) => {
                setStatusFilter(
                  event.target.value as StatusFilter,
                );
              }}
            >
              <option value="TODAS">Status: todas</option>
              <option value="PENDENTES">Pendentes</option>
              <option value="CONCLUIDAS">
                Concluídas
              </option>
            </select>
            <select
              value={String(prioridadeFilter)}
              aria-label="Filtrar por prioridade"
              onChange={(event) => {
                const value = event.target.value;
                setPrioridadeFilter(
                  value === "TODAS"
                    ? "TODAS"
                    : (Number(value) as TarefaPrioridade),
                );
              }}
            >
              <option value="TODAS">
                Prioridade: todas
              </option>
              {PRIORIDADES.map((option) => (
                <option
                  key={option.value}
                  value={option.value}
                >
                  {option.label} · {option.hint}
                </option>
              ))}
            </select>
          </div>
        )}
      >

        {obras.length > 0 ? (
          <nav
            className="tarefas-obra-tabs"
            aria-label="Obras"
          >
            {obras.map((obra) => (
              <button
                key={obra.id}
                type="button"
                className={
                  obra.id === focusedObraId
                    ? "chip chip--active"
                    : "chip"
                }
                onClick={() => setFocusedObraId(obra.id)}
              >
                {obra.nome || obra.codigoContrato}
              </button>
            ))}
          </nav>
        ) : null}

        {loadError && (
          <p className="tarefas-error" role="alert">
            {loadError}
          </p>
        )}

        {!focusedObraId ? (
          <section className="tarefas-card tarefas-card--empty">
            {isLoading ? (
              <p>Carregando obras…</p>
            ) : (
              <p>
                Nenhuma obra disponível. Conecte-se uma vez para carregar suas obras.
              </p>
            )}
          </section>
        ) : (
          <div className="tarefas-layout">
            <section className="tarefas-card">
              {podeApagarEquipe && abasDaObra.some((aba) => aba.equipe) && (
                <section
                  className="tarefas-limpeza"
                  aria-label="Equipes que sobraram nesta obra"
                >
                  <p>
                    A tarefa agora é da obra, não de uma equipe. Estas equipes
                    ficaram para trás e podem ser apagadas — as tarefas
                    continuam onde estão.
                  </p>
                  <ul>
                    {abasDaObra
                      .filter((aba) => aba.equipe)
                      .map((aba) => (
                        <li key={chaveDaEquipe(aba.nome)}>
                          <span>{aba.nome}</span>
                          <button
                            type="button"
                            disabled={equipeEmExclusao === aba.equipe?.id}
                            onClick={() => {
                              setErroDaExclusao("");
                              setConfirmacaoDeExclusao(aba);
                            }}
                          >
                            Apagar
                          </button>
                        </li>
                      ))}
                  </ul>
                  {erroDaExclusao && (
                    <p role="alert" className="tarefas-limpeza-erro">
                      {erroDaExclusao}
                    </p>
                  )}
                </section>
              )}

              {confirmacaoDeExclusao && (
                <div
                  className="tarefas-confirma"
                  role="dialog"
                  aria-modal="true"
                  aria-label={`Apagar a equipe ${confirmacaoDeExclusao.nome}`}
                >
                  <p>
                    Apagar <strong>{confirmacaoDeExclusao.nome}</strong> de
                    vez? Isso não apaga nenhuma tarefa nem nenhum RDO — some
                    apenas a equipe, para todo mundo.
                  </p>
                  <div className="tarefas-confirma-acoes">
                    <button
                      type="button"
                      onClick={() => setConfirmacaoDeExclusao(null)}
                    >
                      Cancelar
                    </button>
                    <button
                      type="button"
                      className="tarefas-confirma-perigo"
                      disabled={
                        equipeEmExclusao === confirmacaoDeExclusao.equipe?.id
                      }
                      onClick={() => void handleApagarEquipe()}
                    >
                      Apagar equipe
                    </button>
                  </div>
                </div>
              )}

              {isDetalhesLoading ? (
                <p className="tarefas-vazio">
                  Carregando tarefas da obra…
                </p>
              ) : (
                <>
                  <ul className="tarefas-lista">
                    {tarefasVisiveis.length === 0 && (
                      <li className="tarefas-vazio">
                        Nenhuma tarefa neste recorte para
                        {" "}{focusedObra?.nome ?? "esta obra"}.
                      </li>
                    )}
                    {tarefasVisiveis.map((tarefa) => {
                      const prio = prioridadeInfo(
                        tarefa.prioridade,
                      );

                      return (
                        <li
                          key={tarefa.id}
                          className={
                            tarefa.concluida
                              ? "tarefa-item tarefa-item--concluida"
                              : "tarefa-item"
                          }
                        >
                          <label className="tarefa-check">
                            <input
                              type="checkbox"
                              checked={tarefa.concluida}
                              onChange={() => {
                                void handleToggleConclusao(
                                  tarefa,
                                );
                              }}
                              aria-label={`Concluir tarefa ${tarefa.titulo}`}
                            />
                          </label>

                          <div className="tarefa-corpo">
                            <div className="tarefa-linha-titulo">
                              <strong>
                                {tarefa.titulo}
                              </strong>
                              <span
                                className={`tarefa-prio tarefa-prio--${tarefa.prioridade}`}
                              >
                                <BandeiraPrioridade
                                  prioridade={
                                    tarefa.prioridade
                                  }
                                />
                                {prio.label} · {prio.hint}
                              </span>
                            </div>
                            {tarefa.observacoes && (
                              <p className="tarefa-observacoes">
                                {tarefa.observacoes}
                              </p>
                            )}
                            <p className="tarefa-meta">
                              Criada por{" "}
                              {tarefa.criadaPor || "—"} em{" "}
                              {formatDate(
                                tarefa.createdAt,
                              )}
                              {tarefa.responsavelEquipe
                                ? ` · Responsável: ${tarefa.responsavelEquipe}`
                                : ""}
                              {tarefa.concluida &&
                              tarefa.concluidaEm
                                ? ` · Concluída em ${formatDate(tarefa.concluidaEm)}`
                                : ""}
                            </p>
                          </div>

                          <button
                            type="button"
                            className="tarefa-excluir"
                            title="Excluir tarefa"
                            aria-label={`Excluir tarefa ${tarefa.titulo}`}
                            onClick={() => {
                              void handleDeleteTarefa(
                                tarefa,
                              );
                            }}
                          >
                            ×
                          </button>
                        </li>
                      );
                    })}
                  </ul>

                  <form
                    className="tarefa-form"
                    onSubmit={(event) => {
                      void handleCreateTarefa(event);
                    }}
                  >
                    <h3>
                      Nova tarefa · {selectedEquipe}
                    </h3>
                    <div className="tarefa-form-linha">
                      <input
                        value={titulo}
                        placeholder="Título da tarefa"
                        aria-label="Título da tarefa"
                        onChange={(event) =>
                          setTitulo(event.target.value)
                        }
                      />
                      <div
                        className="tarefa-prioridade-grupo"
                        role="group"
                        aria-label="Prioridade da tarefa"
                      >
                        {PRIORIDADES.map((option) => (
                          <button
                            key={option.value}
                            type="button"
                            className={
                              option.value === prioridade
                                ? `tarefa-prio-botao tarefa-prio-botao--ativo tarefa-prio-botao--${option.value}`
                                : "tarefa-prio-botao"
                            }
                            aria-pressed={
                              option.value === prioridade
                            }
                            title={`Prioridade ${option.hint}`}
                            onClick={() =>
                              setPrioridade(option.value)
                            }
                          >
                            <BandeiraPrioridade
                              prioridade={option.value}
                            />
                            {option.label}
                          </button>
                        ))}
                      </div>
                    </div>
                    <textarea
                      value={observacoes}
                      placeholder="Observações (opcional)"
                      aria-label="Observações da tarefa"
                      rows={2}
                      onChange={(event) =>
                        setObservacoes(event.target.value)
                      }
                    />
                    <div className="tarefa-form-linha">
                      <div className="tarefa-responsavel">
                        <input
                          value={responsavel}
                          placeholder="Responsável da equipe (Academy)"
                          aria-label="Responsável da equipe"
                          autoComplete="off"
                          onChange={(event) => {
                            const valor =
                              event.target.value;
                            setResponsavel(valor);
                            setResponsavelReconhecido(
                              null,
                            );
                            setIsSugestoesAbertas(true);
                            buscarNoAcademy(valor);
                          }}
                          onFocus={() =>
                            setIsSugestoesAbertas(true)
                          }
                          onBlur={() => {
                            // Deixa o clique na sugestão acontecer antes de fechar.
                            window.setTimeout(
                              () =>
                                setIsSugestoesAbertas(
                                  false,
                                ),
                              150,
                            );
                          }}
                        />
                        {isSugestoesAbertas &&
                          sugestoesResponsavel.length >
                            0 && (
                            <ul
                              className="tarefa-sugestoes"
                              aria-label="Colaboradores do Academy"
                            >
                              {sugestoesResponsavel.map(
                                (colaborador) => (
                                  <li
                                    key={colaborador.id}
                                  >
                                    <button
                                      type="button"
                                      onClick={() => {
                                        setResponsavel(
                                          colaborador.nome,
                                        );
                                        setResponsavelReconhecido(
                                          {
                                            id: colaborador.id,
                                            nome: colaborador.nome,
                                          },
                                        );
                                        setIsSugestoesAbertas(
                                          false,
                                        );
                                      }}
                                    >
                                      <strong>
                                        {colaborador.nome}
                                      </strong>
                                      {colaborador.nomePerfil && (
                                        <small>
                                          {
                                            colaborador.nomePerfil
                                          }
                                        </small>
                                      )}
                                    </button>
                                  </li>
                                ),
                              )}
                            </ul>
                          )}
                        {responsavelReconhecido && (
                          <p className="tarefa-reconhecido">
                            ✓ Reconhecido no Academy:{" "}
                            {responsavelReconhecido.nome}
                          </p>
                        )}
                      </div>
                      <button
                        type="submit"
                        className="tarefa-form-enviar"
                        disabled={isSaving}
                      >
                        {isSaving
                          ? "Salvando…"
                          : "Criar tarefa"}
                      </button>
                    </div>
                    {formError && (
                      <p
                        className="tarefas-error"
                        role="alert"
                      >
                        {formError}
                      </p>
                    )}
                  </form>
                </>
              )}
            </section>

            <aside className="tarefas-card tarefas-eficiencia">
              <h3>Eficiência de conclusão</h3>
              <p className="tarefas-eficiencia-resumo">
                {resumo.total === 0 ? (
                  "Sem tarefas para medir ainda."
                ) : (
                  <>
                    <strong>
                      {resumo.concluidas} de{" "}
                      {resumo.total}
                    </strong>{" "}
                    tarefas concluídas
                    {resumo.percentual !== null
                      ? ` · ${resumo.percentual.toLocaleString("pt-BR")}%`
                      : ""}
                  </>
                )}
              </p>
              {periodoLabel && (
                <p className="tarefas-eficiencia-periodo">
                  {periodoLabel}
                </p>
              )}
              <TarefaEficienciaChart
                points={serie}
                period={period}
                onPeriodChange={setPeriod}
              />
            </aside>
          </div>
        )}
      </OperationalWorkspace>
    </CortexShell>
  );
}
