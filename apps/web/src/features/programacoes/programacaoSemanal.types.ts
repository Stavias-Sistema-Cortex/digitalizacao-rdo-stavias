export type ProgramacaoFonteTipo = "PDF" | "EXCEL";

export interface ProgramacaoSemanalLinha {
  id: string;
  fechamento: string;
  servico: string;
  obraNome: string;
  cliente: string;
  rodovia: string;
  sentido: string;
  periodo: string;
  kmInicial: string;
  kmFinal: string;
  faixa: string;
  toneladaMassa: number | null;
  tipoCap: string;
  teorCapProjeto: number | null;
  cap: number | null;
  extensaoM: number | null;
  raw: Record<string, string>;
}

export interface ProgramacaoSemanalDia {
  data: string;
  diaSemana: string;
  status: "COM_DADOS" | "BAIXADA" | "VAZIO";
  linhas: ProgramacaoSemanalLinha[];
}

export interface ProgramacaoSemanalImportada {
  arquivoNome: string;
  fonteTipo: ProgramacaoFonteTipo;
  encarregadoExtraido: string;
  dias: ProgramacaoSemanalDia[];
  avisos: string[];
}

export interface ObraResolvida {
  id: string;
  nome: string | null;
  codigoContrato: string | null;
  cliente: string | null;
  rodovia: string | null;
}

export interface EncarregadoResolvido {
  id: string;
  nome: string | null;
  nomePerfil: string | null;
  nomeGrupo: string | null;
}
