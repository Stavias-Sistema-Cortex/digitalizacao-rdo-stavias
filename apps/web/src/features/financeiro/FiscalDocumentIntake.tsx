import { useRef, useState } from "react";

import { enviarDocumentoFiscal } from "./financeiroApi";
import type { FinanceFiscalExtraction } from "./financeiro.types";

const ACCEPTED_FISCAL_FILES = [
  ".xml",
  ".pdf",
  ".jpg",
  ".jpeg",
  ".png",
  ".webp",
  ".tif",
  ".tiff",
  "application/xml",
  "text/xml",
  "application/pdf",
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/tiff",
].join(",");

interface FiscalDocumentIntakeProps {
  obraId: string;
  extraction: FinanceFiscalExtraction | null;
  onExtracted: (extraction: FinanceFiscalExtraction) => void;
  disabled?: boolean;
}

export function FiscalDocumentIntake({
  obraId,
  extraction,
  onExtracted,
  disabled = false,
}: FiscalDocumentIntakeProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [mutationId, setMutationId] = useState("");
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");

  async function upload(selected: File, mutation: string) {
    setUploading(true);
    setError("");
    try {
      onExtracted(await enviarDocumentoFiscal(selected, obraId, mutation));
    } catch (reason: unknown) {
      setError(reason instanceof Error
        ? reason.message
        : "Não foi possível inspecionar o documento.");
    } finally {
      setUploading(false);
    }
  }

  function selectFile(event: React.ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    if (!selected) return;
    const mutation = crypto.randomUUID();
    setFile(selected);
    setMutationId(mutation);
    void upload(selected, mutation);
  }

  return (
    <section className="finance-fiscal-intake" aria-busy={uploading}>
      <div className="finance-fiscal-intake__heading">
        <div>
          <strong>Documento de origem</strong>
          <span>XML, PDF ou imagem. O arquivo será conferido antes do preenchimento.</span>
        </div>
        <button
          type="button"
          className="finance-secondary-action"
          disabled={disabled || uploading}
          onClick={() => inputRef.current?.click()}
        >
          {extraction ? "Trocar arquivo" : "Enviar documento"}
        </button>
        <input
          ref={inputRef}
          className="sr-only"
          type="file"
          accept={ACCEPTED_FISCAL_FILES}
          disabled={disabled || uploading}
          onChange={selectFile}
        />
      </div>

      {uploading ? (
        <div className="finance-fiscal-progress" role="status">
          <span />
          Conferindo formato, hash e campos fiscais…
        </div>
      ) : null}

      {error ? (
        <div className="finance-fiscal-retry" role="alert">
          <p>{error}</p>
          {file && mutationId ? (
            <button
              type="button"
              onClick={() => void upload(file, mutationId)}
            >
              Tentar novamente sem duplicar
            </button>
          ) : null}
        </div>
      ) : null}

      {extraction ? <ExtractionReview extraction={extraction} /> : null}
    </section>
  );
}

function ExtractionReview({
  extraction,
}: {
  extraction: FinanceFiscalExtraction;
}) {
  return (
    <div className="finance-fiscal-review">
      <header>
        <div>
          <strong>{extraction.fileName}</strong>
          <span>
            {Math.max(1, Math.ceil(extraction.size / 1024))} KB · {extraction.format}
          </span>
        </div>
        <span className={`finance-fiscal-status is-${extraction.status.toLowerCase()}`}>
          {statusLabel(extraction.status)}
        </span>
      </header>

      <dl>
        <div>
          <dt>Hash conferido</dt>
          <dd title={extraction.serverSha256}>{shortHash(extraction.serverSha256)}</dd>
        </div>
        <div>
          <dt>Extrator</dt>
          <dd>{extraction.extractor
            ? `${extraction.extractor} v${extraction.extractorVersion ?? "—"}`
            : "Não executado"}</dd>
        </div>
        <div>
          <dt>Autorização fiscal</dt>
          <dd>Não verificada</dd>
        </div>
      </dl>

      {extraction.warnings.length > 0 ? (
        <ul className="finance-fiscal-warnings">
          {extraction.warnings.map((warning) => (
            <li key={warning}>{warningLabel(warning)}</li>
          ))}
        </ul>
      ) : null}

      {extraction.candidates.length > 0 ? (
        <div className="finance-fiscal-candidates">
          <p>Campos encontrados</p>
          <ul>
            {extraction.candidates.map((candidate) => (
              <li
                key={`${candidate.field}-${candidate.location ?? ""}`}
                className={candidate.validation === "DIVERGENTE"
                  ? "is-divergent"
                  : undefined}
              >
                <div>
                  <strong>{fieldLabel(candidate.field)}</strong>
                  <span>{displayValue(candidate.normalizedValue)}</span>
                </div>
                <small>
                  {Math.round(candidate.confidence * 100)}% · {candidate.location ?? "sem localização"}
                </small>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function statusLabel(status: FinanceFiscalExtraction["status"]): string {
  if (status === "EXTRAIDO") return "Extraído";
  if (status === "REVISAO_NECESSARIA") return "Revisar";
  return "Falhou";
}

function shortHash(hash: string): string {
  return `${hash.slice(0, 12)}…${hash.slice(-8)}`;
}

function warningLabel(value: string): string {
  const labels: Record<string, string> = {
    REVISAO_HUMANA_OBRIGATORIA: "Revise os campos antes de confirmar.",
    TOTAL_DIVERGENTE: "O total declarado não fecha com os componentes.",
    CHAVE_ACESSO_INVALIDA: "A chave de acesso precisa de conferência.",
    OCR_INDISPONIVEL: "OCR indisponível; preencha os campos manualmente.",
    PDF_SEM_CAMADA_TEXTO: "O PDF não possui texto selecionável.",
    CAMPOS_OBRIGATORIOS_AUSENTES: "Alguns campos obrigatórios não foram encontrados.",
    CAMPOS_FISCAIS_NAO_IDENTIFICADOS: "Nenhum campo fiscal foi identificado.",
    HASH_CLIENTE_DIVERGENTE: "O hash local não confere com o arquivo armazenado.",
  };
  return labels[value] ?? value.replaceAll("_", " ").toLocaleLowerCase("pt-BR");
}

function fieldLabel(value: string): string {
  const labels: Record<string, string> = {
    numero: "Número",
    serie: "Série",
    chaveAcesso: "Chave de acesso",
    tipoDocumento: "Tipo",
    dataEmissao: "Emissão",
    moeda: "Moeda",
    valorBruto: "Valor bruto",
    desconto: "Desconto",
    acrescimo: "Acréscimo",
    retencoes: "Retenções",
    valorLiquido: "Valor líquido",
    fornecedorCnpj: "CNPJ do fornecedor",
    fornecedorNome: "Fornecedor",
  };
  return labels[value] ?? value;
}

function displayValue(value: unknown): string {
  if (typeof value === "string" || typeof value === "number") {
    return String(value);
  }
  return "—";
}
