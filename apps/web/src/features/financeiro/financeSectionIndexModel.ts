export type FinanceSectionGroup = "Receita operacional";
export type ActiveFinanceSection =
  | "receita"
  | "servicos-precos"
  | "pdor";

export const FINANCE_SECTIONS = [
  {
    id: "receita",
    label: "Rastreio de receita",
    group: "Receita operacional",
  },
  {
    id: "servicos-precos",
    label: "Serviços e preços",
    group: "Receita operacional",
  },
  { id: "pdor", label: "PDOR", group: "Receita operacional" },
] as const satisfies readonly {
  id: ActiveFinanceSection;
  label: string;
  group: FinanceSectionGroup;
}[];

export const FINANCE_SECTION_GROUPS: readonly FinanceSectionGroup[] = [
  "Receita operacional",
];
