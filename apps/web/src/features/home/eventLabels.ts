const EVENT_LABELS: Record<string, string> = {
  RDO_CRIADO: "RDO criado",
  RDO_EDITADO: "RDO editado",
  RDO_SALVO_OFFLINE: "RDO salvo offline",
  RDO_SINCRONIZADO: "RDO sincronizado",
  RDO_FALHA_SYNC: "Falha ao sincronizar RDO",
  FOTO_ADICIONADA: "Foto adicionada",
  FOTO_COMPRIMIDA: "Foto comprimida",
  FOTO_REMOVIDA: "Foto removida",
  MEDICAO_TRECHO_ATUALIZADA: "Medição de trecho atualizada",
  COLABORADOR_ASSOCIADO_RDO: "Colaborador associado ao RDO",
  EQUIPAMENTO_ASSOCIADO_RDO: "Equipamento associado ao RDO",
  OCORRENCIA_REGISTRADA: "Ocorrência registrada",
  CALCULO_REPROCESSADO: "Cálculo reprocessado",
  TAREFA_CRIADA: "Tarefa criada",
  TAREFA_ATUALIZADA: "Tarefa atualizada",
  TAREFA_CONCLUIDA: "Tarefa concluída",
  TAREFA_REABERTA: "Tarefa reaberta",
  TAREFA_EXCLUIDA: "Tarefa excluída",
};

export function operationalEventLabel(
  type: string,
): string {
  return EVENT_LABELS[type] ?? "Atividade registrada";
}
