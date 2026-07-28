import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

import { OperationalWorkspace } from "../../../components/workspace/OperationalWorkspace";

import {
  alterarPapelColaborador,
  filtrarObrasOperacionais,
  listarColaboradores,
  listarObrasAdmin,
  listarVinculos,
  queueRevogarVinculo,
  queueVinculoColaborador,
  type ColaboradorApi,
  type ObraAdminApi,
  type VinculoApi,
} from "./gestaoObrasApi";
import { NovaObraForm } from "./NovaObraForm";
import "./gestaoObras.css";

function mensagemErro(erro: unknown): string {
  return erro instanceof Error
    ? erro.message
    : "Ocorreu um erro inesperado.";
}

export function GestaoObrasPage() {
  const [obras, setObras] = useState<ObraAdminApi[]>([]);
  const [obraQuery, setObraQuery] = useState("");
  const [obraQueryAtiva, setObraQueryAtiva] = useState("");
  const [obrasReloadKey, setObrasReloadKey] = useState(0);
  const [obrasErro, setObrasErro] = useState<string | null>(null);
  const [carregandoObras, setCarregandoObras] = useState(true);

  const [obraSelecionadaId, setObraSelecionadaId] =
    useState<string | null>(null);

  const [vinculos, setVinculos] = useState<VinculoApi[]>([]);
  const [vinculosReloadKey, setVinculosReloadKey] = useState(0);
  const [vinculosErro, setVinculosErro] = useState<string | null>(null);
  const [carregandoVinculos, setCarregandoVinculos] = useState(false);

  const [colabQuery, setColabQuery] = useState("");
  const [colaboradores, setColaboradores] = useState<ColaboradorApi[]>([]);
  const [colabSelecionado, setColabSelecionado] = useState("");
  const [salvandoVinculo, setSalvandoVinculo] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);
  const [papelQuery, setPapelQuery] = useState("");
  const [papelColaboradores, setPapelColaboradores] = useState<
    ColaboradorApi[]
  >([]);
  const [papelDestinos, setPapelDestinos] = useState<
    Record<string, "ALFA" | "BETA">
  >({});
  const [papelJustificativas, setPapelJustificativas] = useState<
    Record<string, string>
  >({});
  const [papelSalvandoId, setPapelSalvandoId] = useState<
    string | null
  >(null);

  const obraSelecionada = useMemo(
    () => obras.find((obra) => obra.id === obraSelecionadaId) ?? null,
    [obras, obraSelecionadaId],
  );

  // Os efeitos apenas buscam dados e aplicam o resultado em callbacks assíncronos
  // (nunca setState síncrono no corpo do efeito). Os manipuladores de evento
  // disparam recargas ligando o indicador e incrementando a chave de recarga.
  useEffect(() => {
    let cancelado = false;
    listarObrasAdmin(obraQueryAtiva)
      .then((dados) => {
        if (!cancelado) {
          const operacionais = filtrarObrasOperacionais(dados);
          setObras(operacionais);
          setObraSelecionadaId((selecionada) =>
            operacionais.some((obra) => obra.id === selecionada)
              ? selecionada
              : null
          );
          setObrasErro(null);
        }
      })
      .catch((erro: unknown) => {
        if (!cancelado) {
          setObrasErro(mensagemErro(erro));
        }
      })
      .finally(() => {
        if (!cancelado) {
          setCarregandoObras(false);
        }
      });
    return () => {
      cancelado = true;
    };
  }, [obraQueryAtiva, obrasReloadKey]);

  useEffect(() => {
    if (!obraSelecionadaId) {
      return;
    }
    const obraId = obraSelecionadaId;
    let cancelado = false;
    listarVinculos(obraId)
      .then((dados) => {
        if (!cancelado) {
          setVinculos(dados);
          setVinculosErro(null);
        }
      })
      .catch((erro: unknown) => {
        if (!cancelado) {
          setVinculosErro(mensagemErro(erro));
        }
      })
      .finally(() => {
        if (!cancelado) {
          setCarregandoVinculos(false);
        }
      });
    return () => {
      cancelado = true;
    };
  }, [obraSelecionadaId, vinculosReloadKey]);

  function selecionarObra(id: string) {
    setObraSelecionadaId(id);
    setCarregandoVinculos(true);
    setVinculosReloadKey((chave) => chave + 1);
  }

  function submeterBuscaObras(event: FormEvent) {
    event.preventDefault();
    setCarregandoObras(true);
    setObraQueryAtiva(obraQuery);
    setObrasReloadKey((chave) => chave + 1);
  }

  async function buscarColaboradores(event: FormEvent) {
    event.preventDefault();
    try {
      setColaboradores(await listarColaboradores(colabQuery));
    } catch (erro) {
      setAviso(mensagemErro(erro));
    }
  }

  async function buscarPapeis(event: FormEvent) {
    event.preventDefault();
    setAviso(null);
    try {
      const encontrados = await listarColaboradores(papelQuery);
      setPapelColaboradores(encontrados);
      setPapelDestinos(Object.fromEntries(
        encontrados.map((colaborador) => [
          colaborador.id,
          colaborador.papelAcesso,
        ]),
      ));
    } catch (erro) {
      setAviso(mensagemErro(erro));
    }
  }

  async function alterarPapel(colaborador: ColaboradorApi) {
    const destino = papelDestinos[colaborador.id];
    const justificativa = papelJustificativas[colaborador.id] ?? "";
    if (!destino || destino === colaborador.papelAcesso) {
      return;
    }
    if (justificativa.trim().length < 8) {
      setAviso("Informe uma justificativa com pelo menos 8 caracteres.");
      return;
    }
    setPapelSalvandoId(colaborador.id);
    setAviso(null);
    try {
      const alteracao = await alterarPapelColaborador(
        colaborador.id,
        destino,
        justificativa,
      );
      setPapelColaboradores((atuais) => atuais.map((item) =>
        item.id === colaborador.id
          ? { ...item, papelAcesso: alteracao.papelAcesso }
          : item
      ));
      setPapelJustificativas((atuais) => ({
        ...atuais,
        [colaborador.id]: "",
      }));
      setAviso(
        `${colaborador.nome ?? "Colaborador"} agora é ${alteracao.papelAcesso}. `
          + `Registro Cortex ${alteracao.commitSeq}.`,
      );
    } catch (erro) {
      setAviso(mensagemErro(erro));
    } finally {
      setPapelSalvandoId(null);
    }
  }

  async function adicionarVinculo() {
    if (!obraSelecionadaId || !colabSelecionado) {
      return;
    }
    setSalvandoVinculo(true);
    setAviso(null);
    try {
      const pending = await queueVinculoColaborador(
        obraSelecionadaId,
        colabSelecionado,
      );
      setVinculos((current) => [pending, ...current]);
      setColabSelecionado("");
      setAviso(
        "Vínculo pendente de revalidação Alfa; o sync enviará a solicitação automaticamente.",
      );
    } catch (erro) {
      setAviso(mensagemErro(erro));
    } finally {
      setSalvandoVinculo(false);
    }
  }

  async function revogar(vinculo: VinculoApi) {
    if (!obraSelecionadaId) {
      return;
    }
    setAviso(null);
    try {
      const pending = await queueRevogarVinculo(vinculo);
      setVinculos((current) => current.map((item) =>
        item.id === vinculo.id ? pending : item
      ));
      setAviso(
        "Revogação pendente de revalidação Alfa; o sync a enviará automaticamente.",
      );
    } catch (erro) {
      setAviso(mensagemErro(erro));
    }
  }

  const vinculosAtivos = vinculos.filter(
    (v) => v.status === "ATIVO" || v.status === "PENDENTE",
  );
  const vinculosRevogados = vinculos.filter(
    (v) => v.status !== "ATIVO" && v.status !== "PENDENTE",
  );

  return (
    <OperationalWorkspace
      className="gestao-obras"
      eyebrow="Administração Alfa · Escopo global"
      title="Gestão de obras"
      description="Cadastro, vínculos de acesso e papéis preservados com identidade e justificativa."
      status={{
        code: carregandoObras ? "SYNCING" : obrasErro ? "REJECTED" : "SYNCED",
        label: carregandoObras ? "Carregando obras" : `${obras.length} obras no escopo global`,
      }}
    >

      {aviso && <p className="gestao-obras-aviso">{aviso}</p>}

      <div className="gestao-obras-grid">
        <section className="gestao-obras-coluna" aria-label="Obras">
          <form onSubmit={submeterBuscaObras} className="gestao-obras-busca">
            <input
              type="search"
              value={obraQuery}
              onChange={(event) => setObraQuery(event.target.value)}
              placeholder="Buscar obra (código, nome, cidade)"
              aria-label="Buscar obra"
            />
            <button type="submit">Buscar</button>
          </form>

          {carregandoObras && <p>Carregando obras…</p>}
          {obrasErro && <p className="gestao-obras-erro">{obrasErro}</p>}

          <ul className="gestao-obras-lista">
            {obras.map((obra) => (
              <li key={obra.id}>
                <button
                  type="button"
                  className={
                    obra.id === obraSelecionadaId
                      ? "gestao-obras-item ativo"
                      : "gestao-obras-item"
                  }
                  onClick={() => selecionarObra(obra.id)}
                >
                  <span className="gestao-obras-item-nome">
                    {obra.nome ?? obra.codigoContrato ?? obra.id}
                  </span>
                  <span className="gestao-obras-item-meta">
                    {[obra.codigoContrato, obra.cidade, obra.uf]
                      .filter(Boolean)
                      .join(" · ")}
                    {obra.status ? ` · ${obra.status}` : ""}
                  </span>
                </button>
              </li>
            ))}
            {!carregandoObras && obras.length === 0 && (
              <li className="gestao-obras-vazio">Nenhuma obra encontrada.</li>
            )}
          </ul>

          <details className="gestao-obras-nova">
            <summary>Nova obra</summary>
            <NovaObraForm onCreated={(criada) => {
              setAviso(`Obra "${criada.nome ?? criada.codigoContrato}" criada.`);
              setCarregandoObras(true);
              setObraQueryAtiva("");
              setObrasReloadKey((chave) => chave + 1);
              selecionarObra(criada.id);
            }} />
          </details>
        </section>

        <section className="gestao-obras-coluna" aria-label="Vínculos">
          {!obraSelecionada && (
            <p className="gestao-obras-vazio">
              Selecione uma obra para gerenciar os colaboradores vinculados.
            </p>
          )}

          {obraSelecionada && (
            <>
              <h2 className="gestao-obras-titulo">
                {obraSelecionada.nome ?? obraSelecionada.codigoContrato}
              </h2>

              <div className="gestao-obras-add">
                <form onSubmit={buscarColaboradores}>
                  <input
                    type="search"
                    value={colabQuery}
                    onChange={(event) => setColabQuery(event.target.value)}
                    placeholder="Buscar colaborador"
                    aria-label="Buscar colaborador"
                  />
                  <button type="submit">Buscar</button>
                </form>
                <div className="gestao-obras-linha">
                  <select
                    value={colabSelecionado}
                    onChange={(event) =>
                      setColabSelecionado(event.target.value)
                    }
                    aria-label="Colaborador"
                  >
                    <option value="">Selecione um colaborador…</option>
                    {colaboradores.map((colab) => (
                      <option key={colab.id} value={colab.id}>
                        {colab.nome ?? colab.id}
                        {colab.cpfMascarado ? ` (${colab.cpfMascarado})` : ""}
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    onClick={adicionarVinculo}
                    disabled={!colabSelecionado || salvandoVinculo}
                  >
                    {salvandoVinculo ? "Vinculando…" : "Vincular"}
                  </button>
                </div>
              </div>

              {carregandoVinculos && <p>Carregando vínculos…</p>}
              {vinculosErro && (
                <p className="gestao-obras-erro">{vinculosErro}</p>
              )}

              <h3 className="gestao-obras-subtitulo">Vínculos ativos</h3>
              <ul className="gestao-obras-vinculos">
                {vinculosAtivos.map((v) => (
                  <li key={v.id}>
                    <span>{v.colaboradorNome ?? v.colaboradorId}</span>
                    <button
                      type="button"
                      className="gestao-obras-revogar"
                      onClick={() => revogar(v)}
                    >
                      Revogar
                    </button>
                  </li>
                ))}
                {!carregandoVinculos && vinculosAtivos.length === 0 && (
                  <li className="gestao-obras-vazio">
                    Nenhum colaborador vinculado.
                  </li>
                )}
              </ul>

              {vinculosRevogados.length > 0 && (
                <>
                  <h3 className="gestao-obras-subtitulo">
                    Histórico de revogações
                  </h3>
                  <ul className="gestao-obras-vinculos historico">
                    {vinculosRevogados.map((v) => (
                      <li key={v.id}>
                        <span>{v.colaboradorNome ?? v.colaboradorId}</span>
                        <span className="gestao-obras-item-meta">
                          revogado
                        </span>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </>
          )}
        </section>
      </div>

      <section
        className="gestao-papeis"
        aria-labelledby="gestao-papeis-title"
      >
        <header>
          <div>
            <p className="eyebrow">Governança de acesso</p>
            <h2 id="gestao-papeis-title">Papéis Alfa e Beta</h2>
          </div>
          <p>
            Toda mudança exige justificativa, preserva os vínculos existentes
            e entra no histórico do Cortex.
          </p>
        </header>

        <form className="gestao-papeis-busca" onSubmit={buscarPapeis}>
          <input
            type="search"
            value={papelQuery}
            onChange={(event) => setPapelQuery(event.target.value)}
            placeholder="Buscar colaborador por nome ou e-mail"
            aria-label="Buscar colaborador para alterar papel"
          />
          <button type="submit">Buscar</button>
        </form>

        {papelColaboradores.length === 0 ? (
          <p className="gestao-obras-vazio">
            Busque um colaborador para administrar seu papel de acesso.
          </p>
        ) : (
          <ul className="gestao-papeis-lista">
            {papelColaboradores.map((colaborador) => {
              const destino = papelDestinos[colaborador.id]
                ?? colaborador.papelAcesso;
              const mudando = destino !== colaborador.papelAcesso;
              return (
                <li key={colaborador.id}>
                  <div className="gestao-papeis-identidade">
                    <strong>{colaborador.nome ?? colaborador.id}</strong>
                    <span>{colaborador.nomePerfil ?? "Sem perfil de origem"}</span>
                  </div>
                  <span
                    className={`gestao-papeis-atual is-${colaborador.papelAcesso.toLowerCase()}`}
                  >
                    atual: {colaborador.papelAcesso}
                  </span>
                  <label>
                    Novo papel
                    <select
                      value={destino}
                      onChange={(event) => setPapelDestinos((atuais) => ({
                        ...atuais,
                        [colaborador.id]: event.target.value as "ALFA" | "BETA",
                      }))}
                    >
                      <option value="ALFA">Alfa</option>
                      <option value="BETA">Beta</option>
                    </select>
                  </label>
                  <label className="gestao-papeis-justificativa">
                    Justificativa
                    <input
                      value={papelJustificativas[colaborador.id] ?? ""}
                      onChange={(event) => setPapelJustificativas((atuais) => ({
                        ...atuais,
                        [colaborador.id]: event.target.value,
                      }))}
                      placeholder="Motivo da promoção ou do rebaixamento"
                      maxLength={500}
                    />
                  </label>
                  <button
                    type="button"
                    className="gestao-papeis-salvar"
                    disabled={!mudando || papelSalvandoId === colaborador.id}
                    onClick={() => alterarPapel(colaborador)}
                  >
                    {papelSalvandoId === colaborador.id
                      ? "Salvando…"
                      : "Confirmar mudança"}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </OperationalWorkspace>
  );
}
