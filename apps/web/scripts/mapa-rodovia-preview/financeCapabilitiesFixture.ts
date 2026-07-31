/** Substitui `financeCapabilitiesResolver` no harness de verificação visual. */
export function resolveFinanceCapabilities(obraId: string) {
  return Promise.resolve({
    obraId,
    permissoes: ["FINANCEIRO_VISUALIZAR"],
  });
}
