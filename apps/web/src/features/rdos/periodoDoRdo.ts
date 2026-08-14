import { dataHojeEmBrasilia } from "../../lib/tempo/fusoBrasilia";

export type PeriodFilter = "TODOS" | "HOJE" | "7_DIAS" | "30_DIAS";

function diaUtc(data: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(data);
  if (!match) return null;
  const valor = Date.UTC(
    Number(match[1]),
    Number(match[2]) - 1,
    Number(match[3]),
  );
  return new Date(valor).toISOString().slice(0, 10) === data ? valor : null;
}

/** Compara datas civis sem convertê-las pelo fuso do aparelho. */
export function dataEstaNoPeriodo(
  dataRdo: string,
  period: PeriodFilter,
  agora: Date = new Date(),
): boolean {
  if (period === "TODOS") return true;

  const data = diaUtc(dataRdo);
  const hoje = diaUtc(dataHojeEmBrasilia(agora));
  if (data === null || hoje === null) return false;

  const diffDays = Math.floor((hoje - data) / 86_400_000);
  if (period === "HOJE") return diffDays === 0;
  if (period === "7_DIAS") return diffDays >= 0 && diffDays <= 7;
  return diffDays >= 0 && diffDays <= 30;
}
