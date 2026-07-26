import {
  RdoWorkbookExportError,
  type RdoWorkbookSnapshot,
} from "./rdoWorkbookMapping";

const UNSAFE_GLYPH_MESSAGE =
  "O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.";

function items<T>(value: T[] | undefined): T[] {
  return Array.isArray(value) ? value : [];
}

export function assertPdfRenderableText(
  value: string,
  allowLineBreaks = false,
): void {
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0;
    if (
      allowLineBreaks &&
      (codePoint === 0x0a || codePoint === 0x0d)
    ) {
      continue;
    }
    if (codePoint < 0x20 || codePoint > 0xff) {
      throw new RdoWorkbookExportError(
        "RDO_EXPORT_UNSUPPORTED_PDF_GLYPH",
        UNSAFE_GLYPH_MESSAGE,
      );
    }
  }
}

export function validateOriginalPdfSources(
  snapshot: RdoWorkbookSnapshot,
): void {
  const { obra, rdo } = snapshot;
  if (!rdo) return;
  const workforce = items(rdo.maoObra);
  const equipment = items(rdo.equipamentos);
  const materials = items(rdo.materiais);
  const geometry = items(rdo.controlesGeometricos);
  const services = items(rdo.servicosExecutados);
  const singleLineValues = [
    obra?.nome ?? "",
    obra?.codigoContrato ?? "",
    rdo.numeroRdo,
    rdo.dataRdo,
    rdo.previousRdoNumber,
    rdo.rodovia,
    rdo.turno,
    rdo.horaInicio,
    rdo.horaFim,
    rdo.kmInicialProgramado,
    rdo.kmFinalProgramado,
    rdo.kmInicialInterditado,
    rdo.kmFinalInterditado,
    rdo.apontadorRdo,
    rdo.encarregadoObra,
    rdo.fiscalizacaoCampo,
    ...workforce.flatMap((item) => [
      item.nomeColaborador,
      item.cargo,
    ]),
    ...equipment.flatMap((item) => [
      item.descricao,
      item.prefixo,
    ]),
    ...materials.flatMap((item) => [
      item.materialNome,
      item.unidade,
      item.notaFiscal,
    ]),
    ...geometry.flatMap((item) => [
      item.subtrecho,
      item.numero,
      item.estacaInicial,
      item.estacaFinal,
      item.kmInicial,
      item.kmFinal,
      item.pista,
      item.faixa,
      item.ordemServico,
      item.atividadeObservacoes,
    ]),
    ...services.flatMap((item) => [
      item.servicoNome,
      item.unidade,
      item.trechoInicial,
      item.trechoFinal,
      item.localizacao,
    ]),
  ];
  for (const value of singleLineValues) {
    assertPdfRenderableText(value);
  }
  for (const item of geometry) {
    if (!item.atividadeObservacoes.trim()) {
      assertPdfRenderableText(item.observacoes);
    }
  }

  const observationValues = [
    rdo.observacoes,
    ...workforce.map((item) => item.observacoes),
    ...equipment.map((item) => item.observacoes),
    ...materials.map((item) => item.observacoes),
    ...geometry.map((item) => item.observacoes),
    ...services.map((item) => item.observacoes),
  ];
  for (const value of observationValues) {
    assertPdfRenderableText(value, true);
  }
}
