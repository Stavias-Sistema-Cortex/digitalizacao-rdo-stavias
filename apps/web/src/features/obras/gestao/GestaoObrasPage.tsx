import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";

import {
  listarColaboradores,
  listarObrasAdmin,
  listarVinculos,
  revogarVinculo,
  vincularColaborador,
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
          setObras(dados);
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

  function recarregarVinculos() {
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

  async function adicionarVinculo() {
    if (!obraSelecionadaId || !colabSelecionado) {
      return;
    }
    setSalvandoVinculo(true);
    setAviso(null);
    try {
      await vincularColaborador(obraSelecionadaId, colabSelecionado);
      setColabSelecionado("");
      setAviso("Colaborador vinculado à obra.");
      recarregarVinculos();
    } catch (erro) {
      setAviso(mensagemErro(erro));
    } finally {
      setSalvandoVinculo(false);
    }
  }

  async function revogar(colaboradorId: string) {
    if (!obraSelecionadaId) {
      return;
    }
    setAviso(null);
    try {
      await revogarVinculo(obraSelecionadaId, colaboradorId);
      setAviso("Vínculo revogado.");
      recarregarVinculos();
    } catch (erro) {
      setAviso(mensagemErro(erro));
    }
  }

  const vinculosAtivos = vinculos.filter((v) => v.status === "ATIVO");
  const vinculosRevogados = vinculos.filter((v) => v.status !== "ATIVO");

  return (
    <div className="gestao-obras">
      <header className="gestao-obras-header">
        <div>
          <h1>Gestão de Obras</h1>
          <p className="gestao-obras-escopo">Escopo global (Alfa)</p>
        </div>
      </header>

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
                      onClick={() => revogar(v.colaboradorId)}
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
    </div>
  );
}
