import {
  useEffect,
  useMemo,
  useState,
  type FormEvent,
} from "react";
import { useNavigate, useSearchParams } from "react-router";

import { CortexShell } from "../../components/shell/CortexShell";
import { OperationalWorkspace } from "../../components/workspace/OperationalWorkspace";
import { SeletorDePessoa } from "./SeletorDePessoa";
import { camposQueFaltam, mensagemDoQueFalta } from "./camposQueFaltam";
import type {
  ColaboradorLocalRecord,
  ObraLocalRecord,
} from "../../lib/db/db.types";
import {
  listObrasLocais,
} from "../../lib/db/obraLocalRepository";
import { filterOperationalObras } from "../../lib/db/obraSelectors";
import {
  getSession,
  hasOnlineSession,
  isAlfa,
} from "../auth/authSession";
import { hydrateObrasRelacionadas } from "../home/homeHydration";
import {

  listConversationsApi,
} from "../mensagens/mensagensApi";
import {
  listLocalConversations,
  queueConversation,
  storeServerConversations,
} from "../mensagens/mensagensRepository";
import {
  hidratarColaboradoresAcademy,
  listarColaboradoresConhecidos,
} from "../tarefas/colaboradoresAcademy";
import {
  fetchOperationalRoles,
  fetchTeam,
  fetchTeamHistory,
  fetchTeams,
  fetchTeamWorksites,
  type OperationalRoleDto,
  type TeamDto,
  type TeamHistoryEventDto,
  type TeamMemberDto,
  type TeamWorksiteDto,
} from "./teamApi";
import {
  getLocalTeam,
  listLocalOperationalRoles,
  listLocalTeamHistory,
  listLocalTeams,
  listLocalTeamWorksites,
  putLocalTeam,
  queueArchiveTeam,
  queueCreateTeam,
  queueTeamLinkChange,
  queueUnarchiveTeam,
  queueUpdateTeam,
  replaceLocalOperationalRoles,
  replaceLocalTeamHistory,
  replaceLocalTeams,
  replaceLocalTeamWorksites,
} from "./teamLocalRepository";
import {
  filterTeams,
  teamHistoryLabel,
  type TeamFilters,
} from "./teamViewModel";
import "./EquipesPage.css";

const EMPTY_FILTERS: TeamFilters = {
  search: "",
  obraId: "",
  roleId: "",
  status: "ATIVA",
  activeOn: "",
};

interface TeamFormState {
  nome: string;
  descricao: string;
  obraId: string;
  inicio: string;
  motivo: string;
}

interface MemberFormState {
  colaboradorId: string;
  funcaoOperacionalId: string;
  responsavel: boolean;
  inicio: string;
  motivo: string;
  concederAcessoObra: boolean;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function participantInitials(value: string): string {
  const words = value.trim().split(/\s+/).filter(Boolean);
  return words.slice(0, 2).map((word) => word[0]).join("").toUpperCase() || "?";
}

function localDateTime(date: string): string {
  return `${date}T00:00:00`;
}

function dateOnly(value: string | null): string {
  return value?.slice(0, 10) ?? "";
}

function formatDate(value: string | null): string {
  if (!value) return "Atual";
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(new Date(value));
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function changedFields(event: TeamHistoryEventDto): string[] {
  const fields = event.payload.changedFields;
  return Array.isArray(fields)
    ? fields.filter((field): field is string => typeof field === "string")
    : [];
}

async function fetchAllScopedTeams(): Promise<TeamDto[]> {
  const first = await fetchTeams({ page: 0, size: 100 });
  const teams = [...first.items];
  for (let page = 1; page < first.totalPages; page += 1) {
    teams.push(...(await fetchTeams({ page, size: 100 })).items);
  }
  return teams;
}

export function EquipesPage() {
  const session = getSession();
  const hasAuthenticatedConnection = hasOnlineSession();
  const alfa = isAlfa(session);
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedTeamId = searchParams.get("equipe");
  const [teams, setTeams] = useState<TeamDto[]>([]);
  const [selectedTeam, setSelectedTeam] = useState<TeamDto | null>(null);
  const [roles, setRoles] = useState<OperationalRoleDto[]>([]);
  const [worksites, setWorksites] = useState<TeamWorksiteDto[]>([]);
  const [history, setHistory] = useState<TeamHistoryEventDto[]>([]);
  const [filters, setFilters] = useState<TeamFilters>(EMPTY_FILTERS);
  const [isLoading, setIsLoading] = useState(true);
  const [
    hasConfirmedRemoteHydration,
    setHasConfirmedRemoteHydration,
  ] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [reloadTick, setReloadTick] = useState(0);
  const [obras, setObras] = useState<ObraLocalRecord[]>([]);
  const [collaborators, setCollaborators] = useState<ColaboradorLocalRecord[]>([]);
  const [selectedMember, setSelectedMember] = useState<TeamMemberDto | null>(null);
  const [teamModalMode, setTeamModalMode] = useState<"CREATE" | "EDIT" | "ARCHIVE" | null>(null);
  const [memberModalMode, setMemberModalMode] = useState<"ADD" | "EDIT" | "END" | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [teamForm, setTeamForm] = useState<TeamFormState>({
    nome: "",
    descricao: "",
    obraId: "",
    inicio: today(),
    motivo: "",
  });
  const [memberForm, setMemberForm] = useState<MemberFormState>({
    colaboradorId: "",
    funcaoOperacionalId: "",
    responsavel: false,
    inicio: today(),
    motivo: "",
    concederAcessoObra: true,
  });

  useEffect(() => {
    let cancelled = false;
    async function loadTeams() {
      setIsLoading(true);
      setHasConfirmedRemoteHydration(false);
      setError(null);
      const [localTeams, localRoles, localObras] = await Promise.all([
        listLocalTeams(),
        listLocalOperationalRoles(),
        listObrasLocais(),
      ]);
      if (!cancelled) {
        setTeams(localTeams);
        setRoles(localRoles);
        setObras(filterOperationalObras(localObras));
        setIsLoading(false);
      }
      if (!navigator.onLine || !hasAuthenticatedConnection) return;
      try {
        try {
          await hydrateObrasRelacionadas();
        } catch {
          // A equipe continua funcional com as obras já cacheadas.
        }
        const [remoteTeams, remoteRoles] = await Promise.all([
          fetchAllScopedTeams(),
          fetchOperationalRoles(),
        ]);
        await Promise.all([
          replaceLocalTeams(remoteTeams),
          replaceLocalOperationalRoles(remoteRoles),
        ]);
        if (!cancelled) {
          setTeams(remoteTeams);
          setRoles(remoteRoles);
          setObras(
            filterOperationalObras(await listObrasLocais()),
          );
          setHasConfirmedRemoteHydration(true);
        }
      } catch (loadError: unknown) {
        if (!cancelled && localTeams.length === 0) {
          setError(loadError instanceof Error ? loadError.message : "Não foi possível carregar as equipes.");
        }
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }
    void loadTeams();
    return () => {
      cancelled = true;
    };
  }, [hasAuthenticatedConnection, reloadTick]);

  useEffect(() => {
    if (!selectedTeamId) return;
    let cancelled = false;
    const teamId = selectedTeamId;
    async function applyLocal() {
      const [team, localWorksites, localHistory] = await Promise.all([
        getLocalTeam(teamId),
        listLocalTeamWorksites(teamId),
        alfa ? listLocalTeamHistory(teamId) : Promise.resolve([]),
      ]);
      if (!cancelled) {
        setSelectedTeam(team ?? null);
        setWorksites(localWorksites);
        setHistory(localHistory);
      }
    }
    async function loadDetail() {
      setDetailLoading(true);
      setActionError(null);
      await applyLocal();
      if (!navigator.onLine || !hasAuthenticatedConnection) {
        if (!cancelled) setDetailLoading(false);
        return;
      }
      try {
        const [team, remoteWorksites, remoteHistory] = await Promise.all([
          fetchTeam(teamId),
          fetchTeamWorksites(teamId),
          alfa ? fetchTeamHistory(teamId) : Promise.resolve(null),
        ]);
        await putLocalTeam(team);
        await replaceLocalTeamWorksites(teamId, remoteWorksites);
        if (remoteHistory) await replaceLocalTeamHistory(teamId, remoteHistory.events);
        await applyLocal();
      } catch (loadError: unknown) {
        if (!cancelled && !(await getLocalTeam(teamId))) {
          setActionError(loadError instanceof Error ? loadError.message : "Não foi possível carregar a equipe.");
        }
      } finally {
        if (!cancelled) setDetailLoading(false);
      }
    }
    void loadDetail();
    return () => {
      cancelled = true;
    };
  }, [alfa, hasAuthenticatedConnection, reloadTick, selectedTeamId]);

  const visibleTeams = useMemo(() => filterTeams(teams, filters), [filters, teams]);
  const activeMembers = selectedTeam?.membros.filter((member) => member.status === "ATIVO") ?? [];
  const formerMembers = selectedTeam?.membros.filter((member) => member.status !== "ATIVO") ?? [];

  /**
   * Carrega as pessoas que podem entrar na equipe.
   *
   * <p>O termo importa mais do que parece. O catálogo do Academy responde no
   * máximo cinquenta pessoas por consulta, e sem termo elas são as cinquenta
   * primeiras em ordem alfabética — de modo que, antes de haver busca aqui,
   * ninguém depois da letra B existia nesta tela. Repassar o que está sendo
   * digitado é o que faz a consulta procurar em vez de recortar; o que volta é
   * mesclado ao que o dispositivo já conhece, então digitar acrescenta gente e
   * nunca subtrai.
   */
  async function loadCollaborators(query = "") {
    if (!selectedTeam) return;
    try {
      if (navigator.onLine && hasAuthenticatedConnection) {
        // Montar equipe é ação exclusiva do Alfa, e ele precisa alcançar quem
        // ainda não tem vínculo nenhum com a obra — numa obra recém-criada não
        // há ninguém ligado a ela, e a lista escopada viria vazia justamente
        // no momento em que se quer formar a equipe. O Beta continua restrito
        // ao conjunto da obra a que tem acesso.
        await hidratarColaboradoresAcademy(
          query,
          alfa ? undefined : selectedTeam.obraPrincipalId,
        );
      }
    } catch {
      // Mantém pessoas já conhecidas no dispositivo.
    }
    setCollaborators((await listarColaboradoresConhecidos()).filter(
      (collaborator) => !activeMembers.some((member) => member.colaboradorId === collaborator.id),
    ));
  }

  function openCreateTeam() {
    setTeamForm({ nome: "", descricao: "", obraId: "", inicio: today(), motivo: "" });
    setActionError(null);
    setTeamModalMode("CREATE");
  }

  function openEditTeam() {
    if (!selectedTeam) return;
    setTeamForm({
      nome: selectedTeam.nome,
      descricao: selectedTeam.descricao ?? "",
      obraId: selectedTeam.obraPrincipalId,
      inicio: dateOnly(selectedTeam.inicioValidadeEm),
      motivo: "",
    });
    setActionError(null);
    setTeamModalMode("EDIT");
  }

  async function submitTeam(event: FormEvent) {
    event.preventDefault();
    if (!teamForm.nome.trim() || !teamForm.obraId || !teamForm.inicio) {
      setActionError("Informe nome, obra e início da vigência.");
      return;
    }
    // A edição precisa da equipe carregada para preservar o fim de vigência e
    // a versão base. Uma recarga concluída com o modal aberto pode zerá-la, e
    // aí o salvamento estourava com erro de plataforma na cara de quem estava
    // digitando — em vez de dizer o que houve.
    const equipeEmEdicao = selectedTeam;
    if (teamModalMode === "EDIT" && !equipeEmEdicao) {
      setActionError(
        "A equipe deixou de estar carregada. Feche e abra novamente para editar.",
      );
      return;
    }
    setIsSaving(true);
    setActionError(null);
    try {
      const saved = teamModalMode === "CREATE" || !equipeEmEdicao
        ? await queueCreateTeam({
            id: crypto.randomUUID(),
            obraId: teamForm.obraId,
            obraNome: obras.find((obra) => obra.id === teamForm.obraId)?.nome
              ?? teamForm.obraId,
            nome: teamForm.nome,
            descricao: teamForm.descricao.trim() || null,
            inicioValidadeEm: localDateTime(teamForm.inicio),
          })
        : await queueUpdateTeam(equipeEmEdicao, {
            nome: teamForm.nome,
            descricao: teamForm.descricao.trim() || null,
            inicioValidadeEm: localDateTime(teamForm.inicio),
            fimValidadeEm: equipeEmEdicao.fimValidadeEm,
            motivo: teamForm.motivo,
          });
      setTeams((current) => [
        saved,
        ...current.filter((team) => team.id !== saved.id),
      ]);
      setSelectedTeam(saved);
      setTeamModalMode(null);
      setSearchParams({ equipe: saved.id });
      setReloadTick((value) => value + 1);
    } catch (saveError: unknown) {
      setActionError(saveError instanceof Error ? saveError.message : "Não foi possível salvar a equipe.");
    } finally {
      setIsSaving(false);
    }
  }

  /**
   * Desarquivar é gesto direto: não encerra nada nem pede justificativa, então
   * não abre modal. Só o status volta — membros e vínculos seguem encerrados,
   * porque recompô-los sozinho inventaria mão de obra que ninguém declarou.
   */
  async function desarquivar() {
    if (!selectedTeam) {
      return;
    }
    setIsSaving(true);
    setActionError("");
    try {
      const restored = await queueUnarchiveTeam(selectedTeam);
      setSelectedTeam(restored);
      setTeams((current) => current.map((team) =>
        team.id === restored.id ? restored : team
      ));
      setReloadTick((value) => value + 1);
    } catch (saveError: unknown) {
      setActionError(
        saveError instanceof Error
          ? saveError.message
          : "Não foi possível desarquivar a equipe.",
      );
    } finally {
      setIsSaving(false);
    }
  }

  async function submitArchive(event: FormEvent) {
    event.preventDefault();
    // Arquivar não exige justificativa: o campo continua aberto a quem quiser
    // explicar, mas a falta dele não impede mais o gesto.
    if (!selectedTeam) {
      return;
    }
    setIsSaving(true);
    try {
      const archived = await queueArchiveTeam(
        selectedTeam,
        teamForm.motivo,
      );
      setSelectedTeam(archived);
      setTeams((current) => current.map((team) =>
        team.id === archived.id ? archived : team
      ));
      setTeamModalMode(null);
      setReloadTick((value) => value + 1);
    } catch (saveError: unknown) {
      setActionError(saveError instanceof Error ? saveError.message : "Não foi possível arquivar a equipe.");
    } finally {
      setIsSaving(false);
    }
  }

  async function openAddMember() {
    setMemberForm({
      colaboradorId: "",
      funcaoOperacionalId: roles.find((role) => role.ativo)?.id ?? "",
      responsavel: false,
      inicio: today(),
      motivo: "",
      concederAcessoObra: true,
    });
    setSelectedMember(null);
    setActionError(null);
    setMemberModalMode("ADD");
    await loadCollaborators();
  }

  function openEditMember(member: TeamMemberDto) {
    setSelectedMember(member);
    setMemberForm({
      colaboradorId: member.colaboradorId,
      funcaoOperacionalId: member.funcaoOperacionalId,
      responsavel: member.responsavel,
      inicio: dateOnly(member.inicioEm),
      motivo: "",
      concederAcessoObra: false,
    });
    setActionError(null);
    setMemberModalMode("EDIT");
  }

  async function submitMember(event: FormEvent) {
    event.preventDefault();
    const faltando = camposQueFaltam(memberForm);
    if (!selectedTeam || faltando.length > 0) {
      setActionError(mensagemDoQueFalta(faltando));
      return;
    }
    setIsSaving(true);
    setActionError(null);
    try {
      if (memberModalMode === "ADD") {
        const memberId = crypto.randomUUID();
        const collaborator = collaborators.find(
          (item) => item.id === memberForm.colaboradorId,
        );
        const role = roles.find(
          (item) => item.id === memberForm.funcaoOperacionalId,
        );
        const timestamp = new Date().toISOString();
        const member: TeamMemberDto = {
          id: memberId,
          equipeId: selectedTeam.id,
          colaboradorId: memberForm.colaboradorId,
          colaboradorNome: collaborator?.nome ?? memberForm.colaboradorId,
          papelAcesso: null,
          funcaoOperacionalId: memberForm.funcaoOperacionalId,
          funcaoCodigo: role?.codigo ?? "",
          funcaoNome: role?.nome ?? memberForm.funcaoOperacionalId,
          responsavel: memberForm.responsavel,
          inicioEm: localDateTime(memberForm.inicio),
          status: "ATIVO",
          fimEm: null,
          motivoEncerramento: null,
          versaoEntidade: 0,
          criadoEm: timestamp,
          atualizadoEm: timestamp,
        };
        const updated = await queueTeamLinkChange(selectedTeam, {
          action: "ADICIONAR_MEMBRO",
          vinculo: {
            id: memberId,
            colaboradorId: memberForm.colaboradorId,
            funcaoOperacionalId: memberForm.funcaoOperacionalId,
            responsavel: memberForm.responsavel,
            inicioEm: localDateTime(memberForm.inicio),
            baseVersao: null,
            motivo: memberForm.motivo,
            concederAcessoObra: memberForm.concederAcessoObra,
          },
          projectedMembers: [...selectedTeam.membros, member],
          colaboradorId: memberForm.colaboradorId,
        });
        setSelectedTeam(updated);
        setTeams((current) => current.map((team) =>
          team.id === updated.id ? updated : team
        ));
      } else {
        const member = selectedMember!;
        const role = roles.find(
          (item) => item.id === memberForm.funcaoOperacionalId,
        );
        const projected = {
          ...member,
          funcaoOperacionalId: memberForm.funcaoOperacionalId,
          funcaoCodigo: role?.codigo ?? member.funcaoCodigo,
          funcaoNome: role?.nome ?? member.funcaoNome,
          responsavel: memberForm.responsavel,
          inicioEm: localDateTime(memberForm.inicio),
          atualizadoEm: new Date().toISOString(),
        };
        const updated = await queueTeamLinkChange(selectedTeam, {
          action: "ATUALIZAR_MEMBRO",
          vinculo: {
            id: member.id,
            colaboradorId: member.colaboradorId,
            funcaoOperacionalId: memberForm.funcaoOperacionalId,
            responsavel: memberForm.responsavel,
            inicioEm: localDateTime(memberForm.inicio),
            baseVersao: member.versaoEntidade,
            motivo: memberForm.motivo,
            concederAcessoObra: memberForm.concederAcessoObra,
          },
          projectedMembers: selectedTeam.membros.map((item) =>
            item.id === member.id ? projected : item
          ),
          colaboradorId: member.colaboradorId,
        });
        setSelectedTeam(updated);
        setTeams((current) => current.map((team) =>
          team.id === updated.id ? updated : team
        ));
      }
      setMemberModalMode(null);
      setReloadTick((value) => value + 1);
    } catch (saveError: unknown) {
      setActionError(saveError instanceof Error ? saveError.message : "Não foi possível salvar a participação.");
    } finally {
      setIsSaving(false);
    }
  }

  async function submitEndMember(event: FormEvent) {
    event.preventDefault();
    if (!selectedTeam || !selectedMember || !memberForm.motivo.trim()) {
      setActionError("Informe o motivo do encerramento.");
      return;
    }
    setIsSaving(true);
    try {
      const endedAt = localDateTime(memberForm.inicio);
      const updated = await queueTeamLinkChange(selectedTeam, {
        action: "ENCERRAR_MEMBRO",
        vinculoId: selectedMember.id,
        vinculo: {
          baseVersao: selectedMember.versaoEntidade,
          motivo: memberForm.motivo,
          encerradoEm: endedAt,
        },
        projectedMembers: selectedTeam.membros.map((member) =>
          member.id === selectedMember.id
            ? {
                ...member,
                status: "ENCERRADO",
                fimEm: endedAt,
                motivoEncerramento: memberForm.motivo,
                atualizadoEm: new Date().toISOString(),
              }
            : member
        ),
        colaboradorId: selectedMember.colaboradorId,
      });
      setSelectedTeam(updated);
      setTeams((current) => current.map((team) =>
        team.id === updated.id ? updated : team
      ));
      setMemberModalMode(null);
      setSelectedMember(null);
      setReloadTick((value) => value + 1);
    } catch (saveError: unknown) {
      setActionError(saveError instanceof Error ? saveError.message : "Não foi possível encerrar a participação.");
    } finally {
      setIsSaving(false);
    }
  }

  async function openTeamConversation() {
    if (!selectedTeam) {
      return;
    }
    setActionError(null);
    try {
      // Sem rede, o que o dispositivo já tem é a resposta: uma conversa de
      // equipe que já foi aberta antes está aqui. Só se não houver nenhuma é
      // que uma nova é criada — e ela também nasce local.
      const conversationId = temRede()
        ? await conversaDaEquipeComRede(selectedTeam)
        : await conversaDaEquipeLocal(selectedTeam);
      navigate(`/mensagens?conversa=${encodeURIComponent(conversationId)}`);
    } catch (conversationError: unknown) {
      setActionError(conversationError instanceof Error ? conversationError.message : "Não foi possível abrir a conversa.");
    }
  }

  function temRede() {
    return navigator.onLine && hasAuthenticatedConnection;
  }

  async function conversaDaEquipeComRede(
    equipe: NonNullable<typeof selectedTeam>,
  ): Promise<string> {
    const conversations = await listConversationsApi();
    const existing = conversations.find(
      (conversation) => conversation.equipeId === equipe.id,
    );
    if (existing) {
      await storeServerConversations([existing]);
      return existing.id;
    }
    return conversaDaEquipeLocal(equipe);
  }

  async function conversaDaEquipeLocal(
    equipe: NonNullable<typeof selectedTeam>,
  ): Promise<string> {
    const locais = await listLocalConversations();
    const existente = locais.find(
      (conversation) => conversation.equipeId === equipe.id,
    );
    if (existente) {
      return existente.id;
    }
    const criada = await queueConversation({
      tipo: "EQUIPE",
      titulo: equipe.nome,
      obraId: equipe.obraPrincipalId,
      equipeId: equipe.id,
      participantes: [],
    });
    return criada.id;
  }

  return (
    <CortexShell
      active="equipes"
      onRefresh={() => setReloadTick((value) => value + 1)}
      isRefreshing={isLoading || detailLoading}
    >
      <OperationalWorkspace
        className="teams-workspace"
        eyebrow="Estrutura operacional"
        title="Equipes"
        actions={alfa ? (
          <>
            {/*
              * O catálogo de funções fica a um clique da tela que o consome:
              * quem descobre que falta um cargo está montando equipe, não
              * procurando uma tela de administração.
              */}
            <button
              type="button"
              className="secondary-button"
              onClick={() => navigate("/equipes/funcoes")}
            >
              Funções operacionais
            </button>
            <button type="button" onClick={openCreateTeam}>Criar equipe</button>
          </>
        ) : null}
        status={{
          code: isLoading
            ? "SYNCING"
            : error
              ? "REJECTED"
              : hasConfirmedRemoteHydration
                ? "SYNCED"
                : "LOCAL",
          label: isLoading
            ? "Carregando equipes"
            : error
              ? "Catálogo indisponível"
              : `${visibleTeams.length} equipes visíveis`,
          detail: hasConfirmedRemoteHydration
            ? "Catálogo confirmado pelo servidor"
            : "Dados preservados neste dispositivo",
        }}
      >
      <div className={`teams-page ${selectedTeamId ? "teams-page--detail-open" : ""}`}>
        <aside className="teams-catalog">
          <div className="teams-filters">
            <label className="teams-search"><img src="/icons8/search.png" alt="" /><input value={filters.search} onChange={(event) => setFilters((current) => ({ ...current, search: event.target.value }))} placeholder="Buscar equipe ou membro" /></label>
            <div>
              <select aria-label="Filtrar por obra" value={filters.obraId} onChange={(event) => setFilters((current) => ({ ...current, obraId: event.target.value }))}><option value="">Todas as obras</option>{obras.map((obra) => <option value={obra.id} key={obra.id}>{obra.nome}</option>)}</select>
              <select aria-label="Filtrar por status" value={filters.status} onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value }))}><option value="">Todos os status</option><option value="ATIVA">Ativas</option><option value="ARQUIVADA">Arquivadas</option></select>
            </div>
            <div>
              <select aria-label="Filtrar por função" value={filters.roleId} onChange={(event) => setFilters((current) => ({ ...current, roleId: event.target.value }))}><option value="">Todas as funções</option>{roles.map((role) => <option value={role.id} key={role.id}>{role.nome}</option>)}</select>
              <input type="date" aria-label="Vigente em" value={filters.activeOn} onChange={(event) => setFilters((current) => ({ ...current, activeOn: event.target.value }))} />
            </div>
          </div>
          <div className="teams-list">
            {isLoading && teams.length === 0 ? [1, 2, 3].map((item) => <div className="teams-list-skeleton" key={item}><span /><i /><i /></div>) : error ? <div className="teams-empty-state"><strong>Equipes indisponíveis</strong><p>{error}</p></div> : visibleTeams.length === 0 ? <div className="teams-empty-state"><strong>Nenhuma equipe encontrada</strong><p>Revise os filtros ou crie a primeira equipe desta obra.</p></div> : visibleTeams.map((team) => {
              const members = team.membros.filter((member) => member.status === "ATIVO");
              return <button type="button" className={`teams-list-item ${team.id === selectedTeamId ? "is-active" : ""}`} key={team.id} onClick={() => setSearchParams({ equipe: team.id })}><span><strong>{team.nome}</strong><small>{team.obraNome}</small><em>{members.length} {members.length === 1 ? "membro ativo" : "membros ativos"}</em></span><b className={`teams-status teams-status--${team.status.toLowerCase()}`}>{team.status === "ATIVA" ? "Ativa" : "Arquivada"}</b></button>;
            })}
          </div>
        </aside>

        <section className="teams-detail">
          {!selectedTeamId || !selectedTeam ? <div className="teams-detail-empty"><p>Selecione uma equipe na lista ao lado.</p></div> : <>
            <header className="teams-detail-header">
              <button className="teams-mobile-back" type="button" onClick={() => setSearchParams({})} aria-label="Voltar às equipes">‹</button>
              
              <div><p>{selectedTeam.obraNome}</p><h2>{selectedTeam.nome}</h2><span>ID {selectedTeam.id} · versão {selectedTeam.versaoEntidade}</span></div>
              <div className="teams-detail-actions"><button type="button" onClick={() => navigate(`/obras?obra=${encodeURIComponent(selectedTeam.obraPrincipalId)}`)}>Ver obra</button><button type="button" className="is-primary" onClick={() => void openTeamConversation()}>Abrir conversa</button>{alfa && selectedTeam.status === "ATIVA" && <button type="button" onClick={openEditTeam}>Editar</button>}</div>
            </header>
            {actionError && <div className="teams-action-error" role="alert">{actionError}<button type="button" onClick={() => setActionError(null)}>×</button></div>}
            <div className="teams-detail-scroll">
              <section className="teams-overview">
                <div><span>Status</span><strong className={`teams-status teams-status--${selectedTeam.status.toLowerCase()}`}>{selectedTeam.status === "ATIVA" ? "Ativa" : "Arquivada"}</strong></div>
                <div><span>Vigência</span><strong>{formatDate(selectedTeam.inicioValidadeEm)} — {formatDate(selectedTeam.fimValidadeEm)}</strong></div>
                <div><span>Responsável</span><strong>{activeMembers.find((member) => member.responsavel)?.colaboradorNome ?? "Não definido"}</strong></div>
                <div><span>Última mudança</span><strong>{formatDateTime(selectedTeam.atualizadoEm)}</strong></div>
              </section>
              {selectedTeam.descricao && <p className="teams-description">{selectedTeam.descricao}</p>}

              <section className="teams-section">
                <header><h3>Membros ativos <span className="teams-section-count">{activeMembers.length}</span></h3>{alfa && selectedTeam.status === "ATIVA" && <button type="button" onClick={() => void openAddMember()}>Adicionar pessoa</button>}</header>
                {activeMembers.length === 0 ? <div className="teams-section-empty">Nenhuma participação ativa nesta equipe.</div> : <div className="teams-members-grid">{activeMembers.map((member) => <button type="button" className="teams-member-card" key={member.id} onClick={() => setSelectedMember(member)}><span className="teams-member-avatar">{participantInitials(member.colaboradorNome)}</span><span><strong>{member.colaboradorNome}</strong><small>{member.funcaoNome}</small><em>{member.responsavel ? "Responsável · " : ""}{formatDate(member.inicioEm)}</em></span><b className={`teams-access teams-access--${(member.papelAcesso ?? "beta").toLowerCase()}`}>{member.papelAcesso ?? "Acesso não informado"}</b></button>)}</div>}
              </section>

              <section className="teams-section teams-relations">
                <header><h3>Obras vinculadas</h3></header>
                <div className="teams-relation-list">{worksites.length > 0 ? worksites.map((link) => <article key={link.id}><strong>{link.obraNome}</strong><span>{link.status === "ATIVO" ? "Atuação ativa" : "Vínculo encerrado"} · {formatDate(link.inicioEm)} — {formatDate(link.fimEm)}</span></article>) : <article><strong>{selectedTeam.obraNome}</strong><span>Obra principal da equipe</span></article>}</div>
              </section>

              {formerMembers.length > 0 && <section className="teams-section"><header><h3>Participações encerradas <span className="teams-section-count">{formerMembers.length}</span></h3></header><div className="teams-history-table" role="table"><div role="row"><span>Pessoa</span><span>Função</span><span>Período</span><span>Motivo</span></div>{formerMembers.map((member) => <button type="button" role="row" key={member.id} onClick={() => setSelectedMember(member)}><strong>{member.colaboradorNome}</strong><span>{member.funcaoNome}</span><span>{formatDate(member.inicioEm)} — {formatDate(member.fimEm)}</span><span>{member.motivoEncerramento || "Não informado"}</span></button>)}</div></section>}

              {alfa && <section className="teams-section teams-audit"><header><h3>Registro de alterações <span className="teams-section-count">{history.length}</span></h3></header>{history.length === 0 ? <div className="teams-section-empty">Nenhum evento disponível no cache atual.</div> : <ol>{[...history].reverse().map((event) => <li
                  key={event.eventId}
                  /* commit, origem e id do ator são vocabulário de máquina:
                     não dizem nada a quem gerencia a equipe e davam ao registro
                     cara de saída de depuração. Continuam recuperáveis ao pousar
                     o cursor, que preserva a auditoria sem poluir a leitura. */
                  title={`commit ${event.commitSeq} · ${event.source} · ator ${event.collaboratorId ?? "sistema"}`}
                >
                  <strong>{teamHistoryLabel(event)}</strong>
                  {changedFields(event).length ? (
                    <p>Campos: {changedFields(event).join(", ")}</p>
                  ) : null}
                  <span>{formatDateTime(event.occurredAt)}</span>
                </li>)}</ol>}</section>}

              {alfa && (
                <div className="teams-archive-row">
                  {selectedTeam.status === "ATIVA" ? (
                    <>
                      <p>Arquivar encerra os vínculos ativos e preserva o histórico.</p>
                      <button
                        type="button"
                        onClick={() => {
                          setTeamForm((current) => ({ ...current, motivo: "" }));
                          setTeamModalMode("ARCHIVE");
                        }}
                      >
                        Arquivar equipe
                      </button>
                    </>
                  ) : (
                    <>
                      <p>
                        Desarquivar devolve a equipe ao trabalho. Os membros
                        seguem encerrados e precisam ser adicionados de novo.
                      </p>
                      <button
                        type="button"
                        disabled={isSaving}
                        onClick={() => void desarquivar()}
                      >
                        {isSaving ? "Desarquivando…" : "Desarquivar equipe"}
                      </button>
                    </>
                  )}
                </div>
              )}
            </div>
          </>}
        </section>
      </div>
      </OperationalWorkspace>

      {selectedMember && !memberModalMode && <div className="teams-drawer-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setSelectedMember(null); }}><aside className="teams-member-drawer" aria-label="Detalhes do membro"><header><div className="teams-member-avatar">{participantInitials(selectedMember.colaboradorNome)}</div><button type="button" onClick={() => setSelectedMember(null)}>×</button></header><h2>{selectedMember.colaboradorNome}</h2><p>{selectedMember.funcaoNome}</p><dl><div><dt>Acesso</dt><dd>{selectedMember.papelAcesso ?? "Não informado"}</dd></div><div><dt>Status</dt><dd>{selectedMember.status === "ATIVO" ? "Participação ativa" : "Participação encerrada"}</dd></div><div><dt>Período</dt><dd>{formatDate(selectedMember.inicioEm)} — {formatDate(selectedMember.fimEm)}</dd></div><div><dt>Responsável</dt><dd>{selectedMember.responsavel ? "Sim" : "Não"}</dd></div><div><dt>ID do colaborador</dt><dd><code>{selectedMember.colaboradorId}</code></dd></div><div><dt>ID da participação</dt><dd><code>{selectedMember.id}</code></dd></div></dl>{alfa && selectedMember.status === "ATIVO" && <footer><button type="button" onClick={() => openEditMember(selectedMember)}>Editar participação</button><button type="button" className="is-danger" onClick={() => { setMemberForm((current) => ({ ...current, inicio: today(), motivo: "" })); setMemberModalMode("END"); }}>Encerrar participação</button></footer>}</aside></div>}

      {teamModalMode && <div className="teams-modal-backdrop"><section className="teams-modal" role="dialog" aria-modal="true"><header><h2>{teamModalMode === "CREATE" ? "Criar equipe" : teamModalMode === "EDIT" ? "Editar equipe" : "Arquivar equipe"}</h2><button type="button" onClick={() => setTeamModalMode(null)}>×</button></header>{teamModalMode === "ARCHIVE" ? <form onSubmit={(event) => void submitArchive(event)}><p>A equipe, seus membros e vínculos ativos serão encerrados sem apagar o histórico.</p><label>Motivo (opcional)<textarea value={teamForm.motivo} onChange={(event) => setTeamForm((current) => ({ ...current, motivo: event.target.value }))} /></label>{actionError && <p className="teams-form-error">{actionError}</p>}<footer><button type="button" onClick={() => setTeamModalMode(null)}>Cancelar</button><button className="is-danger" disabled={isSaving}>Confirmar arquivamento</button></footer></form> : <form onSubmit={(event) => void submitTeam(event)}><label>Nome<input maxLength={160} value={teamForm.nome} onChange={(event) => setTeamForm((current) => ({ ...current, nome: event.target.value }))} /></label><label>Obra principal<select disabled={teamModalMode === "EDIT"} value={teamForm.obraId} onChange={(event) => setTeamForm((current) => ({ ...current, obraId: event.target.value }))}><option value="">Escolha uma obra</option>{obras.map((obra) => <option value={obra.id} key={obra.id}>{obra.nome}</option>)}</select></label><label>Início da vigência<input type="date" value={teamForm.inicio} onChange={(event) => setTeamForm((current) => ({ ...current, inicio: event.target.value }))} /></label><label>Descrição<textarea value={teamForm.descricao} onChange={(event) => setTeamForm((current) => ({ ...current, descricao: event.target.value }))} /></label>{teamModalMode === "EDIT" && <label>Motivo da alteração<textarea required value={teamForm.motivo} onChange={(event) => setTeamForm((current) => ({ ...current, motivo: event.target.value }))} /></label>}{actionError && <p className="teams-form-error">{actionError}</p>}<footer><button type="button" onClick={() => setTeamModalMode(null)}>Cancelar</button><button className="is-primary" disabled={isSaving}>{isSaving ? "Salvando…" : "Salvar equipe"}</button></footer></form>}</section></div>}

      {memberModalMode && <div className="teams-modal-backdrop"><section className="teams-modal" role="dialog" aria-modal="true"><header><h2>{memberModalMode === "ADD" ? "Adicionar pessoa" : memberModalMode === "EDIT" ? "Editar participação" : "Encerrar participação"}</h2><button type="button" onClick={() => setMemberModalMode(null)}>×</button></header>{memberModalMode === "END" ? <form onSubmit={(event) => void submitEndMember(event)}><p>O membro continuará disponível no histórico da equipe.</p><label>Data de encerramento<input type="date" value={memberForm.inicio} onChange={(event) => setMemberForm((current) => ({ ...current, inicio: event.target.value }))} /></label><label>Motivo<textarea required value={memberForm.motivo} onChange={(event) => setMemberForm((current) => ({ ...current, motivo: event.target.value }))} /></label>{actionError && <p className="teams-form-error">{actionError}</p>}<footer><button type="button" onClick={() => setMemberModalMode(null)}>Cancelar</button><button className="is-danger" disabled={isSaving}>Encerrar participação</button></footer></form> : <form onSubmit={(event) => void submitMember(event)}><SeletorDePessoa pessoas={memberModalMode === "EDIT" && selectedMember ? [{ id: selectedMember.colaboradorId, nome: selectedMember.colaboradorNome, cpfMascarado: null, nomePerfil: null, ativo: true, updatedAt: null, cachedAt: "" }] : collaborators} selecionadaId={memberForm.colaboradorId} onSelecionar={(colaboradorId) => setMemberForm((current) => ({ ...current, colaboradorId }))} onBuscar={memberModalMode === "EDIT" ? undefined : (termo) => loadCollaborators(termo)} desabilitado={memberModalMode === "EDIT"} /><label>Função operacional<select value={memberForm.funcaoOperacionalId} onChange={(event) => setMemberForm((current) => ({ ...current, funcaoOperacionalId: event.target.value }))}><option value="">Escolha uma função</option>{roles.filter((role) => role.ativo || role.id === memberForm.funcaoOperacionalId).map((role) => <option value={role.id} key={role.id}>{role.nome}</option>)}</select></label><label>Início da participação<input type="date" value={memberForm.inicio} onChange={(event) => setMemberForm((current) => ({ ...current, inicio: event.target.value }))} /></label><label className="teams-check"><input type="checkbox" checked={memberForm.responsavel} onChange={(event) => setMemberForm((current) => ({ ...current, responsavel: event.target.checked }))} /><span>Responsável pela equipe</span></label><label className="teams-check"><input type="checkbox" checked={memberForm.concederAcessoObra} onChange={(event) => setMemberForm((current) => ({ ...current, concederAcessoObra: event.target.checked }))} /><span>Conceder acesso Beta à obra quando necessário</span></label><label>Motivo da alteração<textarea required value={memberForm.motivo} onChange={(event) => setMemberForm((current) => ({ ...current, motivo: event.target.value }))} /></label>{actionError && <p className="teams-form-error">{actionError}</p>}<footer><button type="button" onClick={() => setMemberModalMode(null)}>Cancelar</button><button className="is-primary" disabled={isSaving}>{isSaving ? "Salvando…" : "Salvar participação"}</button></footer></form>}</section></div>}
    </CortexShell>
  );
}
