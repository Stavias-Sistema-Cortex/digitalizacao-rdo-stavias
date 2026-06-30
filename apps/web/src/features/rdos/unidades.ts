/** Unidades de medida comuns em RDO (obras), oferecidas como sugestão. */
export const UNIDADES_RDO = [
  "Unidade",
  "Verba",
  "M",
  "M²",
  "M³",
  "Cm",
  "Km",
  "Kg",
  "Toneladas",
  "Litros",
  "Horas",
  "Dia",
  "Peça",
  "Conjunto",
  "%",
] as const;

/**
 * Normaliza unidades digitadas sem caractere especial para a forma canônica:
 * "m3"/"m^3" -> "m³" e "m2"/"m^2" -> "m²". Assim metros cúbicos podem ser
 * digitados simplesmente como "m3".
 */
export function normalizarUnidade(value: string): string {
  return value
    .replace(/m\^?3\b/gi, "M³")
    .replace(/m\^?2\b/gi, "M²")
    .replace(/m\^?3\b/gi, "m³")
    .replace(/m\^?2\b/gi, "m²");
}
