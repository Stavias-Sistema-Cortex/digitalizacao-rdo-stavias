export interface IntegracaoStatus {
  id: string;
  nome: string;
  estado: string;
  ultimaSincronizacao: string | null;
  duracaoSegundos: number | null;
  registrosLidos: number;
  registrosCriados: number;
  registrosAtualizados: number;
  registrosDesativados: number;
  erro: string | null;
  proximaSincronizacao: string;
  defasagem: string;
}

export interface IntegracaoActionResponse {
  integracao: string;
  status: string;
  mensagem: string;
}
