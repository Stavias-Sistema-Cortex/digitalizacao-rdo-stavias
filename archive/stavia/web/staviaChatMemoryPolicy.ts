/**
 * O chat é apenas uma conveniência local. Quando a política de exposição da
 * ontologia muda, o histórico precisa ser descartado para não reapresentar
 * respostas que já não pertencem a esta superfície.
 */
export const STAVIA_CHAT_MEMORY_POLICY_VERSION =
  "home-memory-only-v1";

export function mustResetStaviaChatHistory(
  storedPolicyVersion: string | null,
): boolean {
  return storedPolicyVersion !== STAVIA_CHAT_MEMORY_POLICY_VERSION;
}
