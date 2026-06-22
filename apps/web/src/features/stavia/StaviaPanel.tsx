import {
  type FormEvent,
  useEffect,
  useState,
} from "react";

import { consultarStavia } from "./staviaApi";
import type {
  StaviaConfidence,
  StaviaConsultaResponse,
} from "./stavia.types";
import "./StaviaPanel.css";

interface StaviaPanelProps {
  initialObraId?: string;
  onBack: () => void;
}

const DEFAULT_WORKSITE_ID =
  "2357081c-7bd8-4e6c-8118-1e25da03461b";

function confidenceLabel(
  confidence: StaviaConfidence,
): string {
  switch (confidence) {
    case "ALTA":
      return "Alta";
    case "MEDIA":
      return "Média";
    case "BAIXA":
      return "Baixa";
    case "INDETERMINADA":
      return "Indeterminada";
  }
}

function formatUpdatedAt(
  value: string | null,
): string {
  if (!value) {
    return "Data não informada";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "medium",
  }).format(date);
}

export function StaviaPanel({
  initialObraId = DEFAULT_WORKSITE_ID,
  onBack,
}: StaviaPanelProps) {
  const [obraId, setObraId] =
    useState(initialObraId);

  const [pergunta, setPergunta] =
    useState(
      "Quais RDOs pertencem a esta obra?",
    );

  const [isOnline, setIsOnline] =
    useState(() => navigator.onLine);

  const [isLoading, setIsLoading] =
    useState(false);

  const [error, setError] =
    useState("");

  const [response, setResponse] =
    useState<StaviaConsultaResponse | null>(
      null,
    );

  useEffect(() => {
    function handleOnline() {
      setIsOnline(true);
    }

    function handleOffline() {
      setIsOnline(false);
    }

    window.addEventListener(
      "online",
      handleOnline,
    );

    window.addEventListener(
      "offline",
      handleOffline,
    );

    return () => {
      window.removeEventListener(
        "online",
        handleOnline,
      );

      window.removeEventListener(
        "offline",
        handleOffline,
      );
    };
  }, []);

  async function handleSubmit(
    event: FormEvent<HTMLFormElement>,
  ) {
    event.preventDefault();

    setError("");
    setResponse(null);

    if (!isOnline) {
      setError(
        "A Stav.IA precisa de conexão para consultar os dados consolidados do Córtex.",
      );
      return;
    }

    if (!obraId.trim()) {
      setError(
        "Informe o identificador da obra.",
      );
      return;
    }

    if (!pergunta.trim()) {
      setError(
        "Digite uma pergunta para a Stav.IA.",
      );
      return;
    }

    setIsLoading(true);

    try {
      const result = await consultarStavia({
        pergunta: pergunta.trim(),
        usuarioId: "frontend-local",
        obraId: obraId.trim(),
      });

      setResponse(result);
    } catch (requestError: unknown) {
      setError(
        requestError instanceof Error
          ? requestError.message
          : "Não foi possível concluir a consulta.",
      );
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <main className="page-shell stavia-page">
      <header className="topbar">
        <div>
          <p className="eyebrow">
            Córtex · Inteligência operacional
          </p>

          <h1>Stav.IA</h1>

          <p className="subtitle">
            Consulte dados operacionais,
            relações, histórico e análises
            disponíveis no Córtex.
          </p>
        </div>

        <div className="workspace-actions">
          <button
            type="button"
            className="secondary-button"
            onClick={onBack}
            disabled={isLoading}
          >
            Voltar aos RDOs
          </button>

          <div className="status-panel">
            <span className="status-label">
              Disponibilidade
            </span>

            <strong
              className={`status-badge ${
                isOnline
                  ? ""
                  : "stavia-status-offline"
              }`}
            >
              {isOnline
                ? "Online"
                : "Sem conexão"}
            </strong>

            <span className="status-id">
              Consulta online
            </span>
          </div>
        </div>
      </header>

      {!isOnline && (
        <div className="notice notice-error">
          A Stav.IA precisa de conexão para
          consultar os dados consolidados do
          Córtex.
        </div>
      )}

      <form
        className="form-card"
        onSubmit={(event) => {
          void handleSubmit(event);
        }}
      >
        <div className="section-heading">
          <div>
            <span className="section-number">
              IA
            </span>

            <h2>Nova consulta</h2>
          </div>

          <p>
            As respostas são limitadas às
            evidências autorizadas encontradas
            para a obra informada.
          </p>
        </div>

        <div className="stavia-form-grid">
          <label>
            Obra
            <input
              type="text"
              value={obraId}
              onChange={(event) => {
                setObraId(
                  event.target.value,
                );
                setError("");
              }}
              placeholder="UUID da obra"
              disabled={isLoading}
            />
            <small>
              Identificador interno da obra no
              Córtex.
            </small>
          </label>

          <label>
            Pergunta
            <textarea
              value={pergunta}
              onChange={(event) => {
                setPergunta(
                  event.target.value,
                );
                setError("");
              }}
              rows={5}
              disabled={isLoading}
              placeholder="Digite sua pergunta operacional."
            />
          </label>
        </div>

        <div className="stavia-suggestions">
          <span>Perguntas sugeridas</span>

          <div>
            {[
              "Quais RDOs pertencem a esta obra?",
              "Qual é o histórico de alterações dos RDOs desta obra?",
              "De qual programação operacional cada RDO desta obra foi gerado?",
              "Qual é o risco de estouro de custos desta obra segundo o PDOC?",
            ].map((suggestion) => (
              <button
                key={suggestion}
                type="button"
                onClick={() => {
                  setPergunta(suggestion);
                  setError("");
                }}
                disabled={isLoading}
              >
                {suggestion}
              </button>
            ))}
          </div>
        </div>

        <div className="stavia-submit-row">
          <button
            type="submit"
            className="primary-button"
            disabled={
              isLoading ||
              !isOnline ||
              !obraId.trim() ||
              !pergunta.trim()
            }
          >
            {isLoading
              ? "Consultando..."
              : "Consultar Stav.IA"}
          </button>
        </div>
      </form>

      {error && (
        <div
          className="notice notice-error"
          role="alert"
        >
          {error}
        </div>
      )}

      {response && (
        <section
          className="form-card stavia-response"
          aria-live="polite"
        >
          <div className="stavia-response-header">
            <div>
              <p className="eyebrow">
                Resposta
              </p>

              <h2>Resultado da consulta</h2>
            </div>

            <span
              className={`stavia-confidence stavia-confidence--${response.answer.confidence.toLowerCase()}`}
            >
              Confiança{" "}
              {confidenceLabel(
                response.answer.confidence,
              )}
            </span>
          </div>

          <p className="stavia-answer-text">
            {response.answer.answer}
          </p>

          <dl className="stavia-response-metadata">
            <div>
              <dt>Intenção identificada</dt>
              <dd>{response.intent}</dd>
            </div>

            <div>
              <dt>Tipo da resposta</dt>
              <dd>
                {response.answer.answerType}
              </dd>
            </div>

            <div>
              <dt>Fontes utilizadas</dt>
              <dd>
                {response.answer.sources.length}
              </dd>
            </div>
          </dl>

          {response.answer.warnings.length >
            0 && (
            <div className="stavia-warning-box">
              <strong>
                Limitações da resposta
              </strong>

              <ul>
                {response.answer.warnings.map(
                  (warning) => (
                    <li key={warning}>
                      {warning}
                    </li>
                  ),
                )}
              </ul>
            </div>
          )}

          {response.knowledgeWarnings.length >
            0 && (
            <div className="stavia-warning-box">
              <strong>
                Avisos das fontes de conhecimento
              </strong>

              <ul>
                {response.knowledgeWarnings.map(
                  (warning) => (
                    <li key={warning}>
                      {warning}
                    </li>
                  ),
                )}
              </ul>
            </div>
          )}

          <div className="stavia-sources">
            <h3>Fontes e evidências</h3>

            {response.answer.sources.length ===
              0 && (
              <p className="subtitle">
                Nenhuma evidência conclusiva foi
                encontrada.
              </p>
            )}

            {response.answer.sources.map(
              (source) => (
                <details
                  key={`${source.type}:${source.id}`}
                  className="stavia-source"
                >
                  <summary>
                    <span>
                      <strong>
                        {source.type}
                      </strong>

                      <small>
                        {source.validated
                          ? "Validada"
                          : "Não validada"}
                      </small>
                    </span>

                    <span>
                      {formatUpdatedAt(
                        source.updatedAt,
                      )}
                    </span>
                  </summary>

                  <p>{source.summary}</p>

                  <dl>
                    <div>
                      <dt>ID</dt>
                      <dd>{source.id}</dd>
                    </div>
                  </dl>

                  <pre>
                    {JSON.stringify(
                      source.attributes,
                      null,
                      2,
                    )}
                  </pre>
                </details>
              ),
            )}
          </div>
        </section>
      )}
    </main>
  );
}
