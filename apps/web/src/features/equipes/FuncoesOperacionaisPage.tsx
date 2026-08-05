import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";

import { OperationalWorkspace } from "../../components/workspace/OperationalWorkspace";
import {
  createOperationalRole,
  fetchOperationalRoles,
  updateOperationalRole,
} from "./teamApi";
import type { OperationalRoleDto } from "./teamApi";
import {
  listLocalOperationalRoles,
  replaceLocalOperationalRoles,
} from "./teamLocalRepository";
import {
  normalizarCodigoDeFuncao,
  problemaNoCodigoDeFuncao,
} from "./codigoDeFuncao";
import "./FuncoesOperacionaisPage.css";

interface FuncoesOperacionaisPageProps {
  onBack: () => void;
}

interface Formulario {
  id: string | null;
  codigo: string;
  nome: string;
  descricao: string;
  ordemExibicao: string;
  ativo: boolean;
  baseVersao: number | null;
}

const VAZIO: Formulario = {
  id: null,
  codigo: "",
  nome: "",
  descricao: "",
  ordemExibicao: "0",
  ativo: true,
  baseVersao: null,
};

/*
 * Editar o catálogo exige rede, e a tela diz isso antes da tentativa.
 *
 * A leitura cai no cache local — o catálogo já é guardado neste dispositivo
 * para os seletores funcionarem sem rede. A escrita não tem esse caminho: não
 * existe operação de função operacional no envelope de sincronização, então
 * enfileirar aqui produziria uma fila que nunca sobe. Prometer offline o que
 * não há é pior que dizer que falta rede.
 */
const SEM_REDE_PARA_ESCREVER =
  "Sem conexão: o catálogo de funções é editado direto no servidor e a"
  + " alteração não fica em fila. Refaça quando a rede voltar.";

function ordemValida(valor: string): number | null {
  const numero = Number(valor);
  if (!Number.isInteger(numero) || numero < 0) {
    return null;
  }
  return numero;
}

export function FuncoesOperacionaisPage({
  onBack,
}: FuncoesOperacionaisPageProps) {
  const [funcoes, setFuncoes] = useState<OperationalRoleDto[]>([]);
  const [formulario, setFormulario] = useState<Formulario | null>(null);
  const [carregando, setCarregando] = useState(true);
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState("");
  const [aviso, setAviso] = useState("");
  const [somenteCache, setSomenteCache] = useState(false);

  const carregar = useCallback(async () => {
    setCarregando(true);
    setErro("");
    try {
      // Inativas incluídas: esta é a tela onde se reativa o que foi desligado,
      // e uma lista que esconde o inativo torna a reativação impossível.
      const remotas = await fetchOperationalRoles(true);
      await replaceLocalOperationalRoles(remotas);
      setFuncoes(remotas);
      setSomenteCache(false);
    } catch (falha: unknown) {
      // O que já está neste dispositivo continua valendo como leitura.
      setFuncoes(await listLocalOperationalRoles());
      setSomenteCache(true);
      setErro(
        falha instanceof Error
          ? falha.message
          : "Não foi possível consultar as funções operacionais.",
      );
    } finally {
      setCarregando(false);
    }
  }, []);

  // Adiado por um tique como em IntegracoesPage: `carregar` marca o estado de
  // carregamento antes do primeiro await, e fazer isso dentro do efeito
  // encadeia renderizações.
  useEffect(() => {
    const id = window.setTimeout(() => {
      void carregar();
    }, 0);
    return () => {
      window.clearTimeout(id);
    };
  }, [carregar]);

  function abrirNova() {
    setErro("");
    setAviso("");
    setFormulario({ ...VAZIO, ordemExibicao: String(funcoes.length + 1) });
  }

  function abrirEdicao(funcao: OperationalRoleDto) {
    setErro("");
    setAviso("");
    setFormulario({
      id: funcao.id,
      codigo: funcao.codigo,
      nome: funcao.nome,
      descricao: funcao.descricao ?? "",
      ordemExibicao: String(funcao.ordemExibicao),
      ativo: funcao.ativo,
      baseVersao: funcao.versaoEntidade,
    });
  }

  async function salvar(evento: FormEvent) {
    evento.preventDefault();
    if (!formulario) return;

    const problemaCodigo = problemaNoCodigoDeFuncao(formulario.codigo);
    if (problemaCodigo) {
      setErro(problemaCodigo);
      return;
    }
    if (!formulario.nome.trim()) {
      setErro("Informe o nome da função.");
      return;
    }
    const ordem = ordemValida(formulario.ordemExibicao);
    if (ordem === null) {
      setErro("A ordem de exibição precisa ser um número inteiro não negativo.");
      return;
    }
    if (!navigator.onLine) {
      setErro(SEM_REDE_PARA_ESCREVER);
      return;
    }

    setSalvando(true);
    setErro("");
    try {
      const dados = {
        codigo: normalizarCodigoDeFuncao(formulario.codigo),
        nome: formulario.nome.trim(),
        descricao: formulario.descricao.trim() || null,
        ativo: formulario.ativo,
        ordemExibicao: ordem,
      };
      if (formulario.id === null) {
        await createOperationalRole(dados);
        setAviso(`Função ${dados.nome} criada.`);
      } else {
        await updateOperationalRole(formulario.id, {
          ...dados,
          baseVersao: formulario.baseVersao ?? 0,
        });
        setAviso(`Função ${dados.nome} atualizada.`);
      }
      setFormulario(null);
      await carregar();
    } catch (falha: unknown) {
      const mensagem = falha instanceof Error ? falha.message : "";
      /*
       * Conflito de versão não é erro de quem digitou: alguém salvou antes.
       * Reler é a saída, e a tela relê sozinha para a próxima tentativa já
       * partir do que existe — pedir que a pessoa "atualize a página" seria
       * transferir a ela um trabalho que a tela sabe fazer.
       */
      if (mensagem.toLowerCase().includes("conflito")) {
        setErro(
          "Outra pessoa alterou esta função enquanto você editava."
          + " A lista foi relida; confira e refaça a alteração.",
        );
        await carregar();
      } else {
        setErro(mensagem || "Não foi possível salvar a função.");
      }
    } finally {
      setSalvando(false);
    }
  }

  const ativas = funcoes.filter((funcao) => funcao.ativo).length;

  return (
    <OperationalWorkspace
      className="funcoes-page"
      eyebrow="Administração"
      title="Funções operacionais"
      description="O catálogo de cargos que as equipes usam para registrar quem faz o quê."
      actions={(
        <>
          <button
            type="button"
            className="secondary-button"
            onClick={() => void carregar()}
            disabled={carregando}
          >
            Atualizar
          </button>
          <button
            type="button"
            className="secondary-button"
            onClick={onBack}
          >
            Voltar às equipes
          </button>
        </>
      )}
      status={{
        code: carregando ? "SYNCING" : erro ? "REJECTED" : "SYNCED",
        label: carregando
          ? "Consultando funções"
          : erro
            ? "Consulta indisponível"
            : `${ativas} ativas de ${funcoes.length}`,
      }}
    >
      {aviso && <div className="notice">{aviso}</div>}

      {erro && (
        <div className="notice notice-error" role="alert">
          {erro}
        </div>
      )}

      {somenteCache && !carregando && (
        <div className="notice" role="status">
          Mostrando o catálogo guardado neste dispositivo. Editar exige rede.
        </div>
      )}

      <section className="form-card funcoes-lista-card">
        <div className="section-heading">
          <h2>Catálogo</h2>
          <button
            type="button"
            className="primary-button"
            onClick={abrirNova}
            disabled={somenteCache}
          >
            Nova função
          </button>
        </div>

        <table className="funcoes-tabela">
          <thead>
            <tr>
              <th>Ordem</th>
              <th>Código</th>
              <th>Nome</th>
              <th>Descrição</th>
              <th>Estado</th>
              <th>Ações</th>
            </tr>
          </thead>
          <tbody>
            {funcoes.map((funcao) => (
              <tr
                key={funcao.id}
                className={funcao.ativo ? undefined : "funcoes-linha-inativa"}
              >
                <td>{funcao.ordemExibicao}</td>
                <td><code>{funcao.codigo}</code></td>
                <td>{funcao.nome}</td>
                <td>{funcao.descricao ?? "—"}</td>
                <td>
                  <span className="status-badge">
                    {funcao.ativo ? "Ativa" : "Inativa"}
                  </span>
                </td>
                <td>
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => abrirEdicao(funcao)}
                    disabled={somenteCache}
                  >
                    Editar
                  </button>
                </td>
              </tr>
            ))}

            {!carregando && funcoes.length === 0 && (
              <tr>
                <td colSpan={6}>
                  {/*
                    * Sem a consulta não se sabe quantas existem; afirmar que
                    * não há nenhuma seria relatar como fato o que ninguém
                    * apurou.
                    */}
                  {erro
                    ? "Não foi possível consultar o catálogo."
                    : "Nenhuma função cadastrada. Crie a primeira para que as equipes possam registrar participações."}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      {formulario && (
        <section className="form-card funcoes-form-card">
          <h2>{formulario.id === null ? "Nova função" : "Editar função"}</h2>

          <form onSubmit={(evento) => void salvar(evento)}>
            <label>
              Código
              <input
                value={formulario.codigo}
                autoFocus
                maxLength={80}
                onChange={(evento) =>
                  setFormulario((atual) =>
                    atual && { ...atual, codigo: evento.target.value },
                  )
                }
              />
              <small>
                Gravado como{" "}
                <code>
                  {normalizarCodigoDeFuncao(formulario.codigo) || "—"}
                </code>
              </small>
            </label>

            <label>
              Nome
              <input
                value={formulario.nome}
                maxLength={160}
                onChange={(evento) =>
                  setFormulario((atual) =>
                    atual && { ...atual, nome: evento.target.value },
                  )
                }
              />
            </label>

            <label>
              Descrição
              <textarea
                value={formulario.descricao}
                onChange={(evento) =>
                  setFormulario((atual) =>
                    atual && { ...atual, descricao: evento.target.value },
                  )
                }
              />
            </label>

            <label>
              Ordem de exibição
              <input
                type="number"
                min={0}
                value={formulario.ordemExibicao}
                onChange={(evento) =>
                  setFormulario((atual) =>
                    atual && { ...atual, ordemExibicao: evento.target.value },
                  )
                }
              />
            </label>

            <label className="funcoes-check">
              <input
                type="checkbox"
                checked={formulario.ativo}
                onChange={(evento) =>
                  setFormulario((atual) =>
                    atual && { ...atual, ativo: evento.target.checked },
                  )
                }
              />
              <span>
                Ativa — aparece na escolha de função ao montar equipe
              </span>
            </label>

            {/*
              * Desativar não apaga: quem já foi registrado com esta função
              * continua registrado, e o histórico segue legível. É a diferença
              * entre parar de oferecer e fingir que nunca existiu.
              */}
            {formulario.id !== null && !formulario.ativo && (
              <p className="funcoes-nota">
                As participações já registradas com esta função continuam como
                estão. Ela apenas deixa de ser oferecida em novas.
              </p>
            )}

            <footer>
              <button
                type="button"
                className="secondary-button"
                onClick={() => setFormulario(null)}
              >
                Cancelar
              </button>
              <button
                type="submit"
                className="primary-button"
                disabled={salvando}
              >
                {salvando ? "Salvando…" : "Salvar função"}
              </button>
            </footer>
          </form>
        </section>
      )}
    </OperationalWorkspace>
  );
}
