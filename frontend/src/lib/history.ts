import { shownQty } from "./units.ts";
import type { IncomingApi, MonthHistoryApi } from "./types";

// 발주 이력 탭 (DECISIONS #62). 지난 발주는 은행 거래 내역처럼 날짜별로, 추천 발주량은 보이지 않는다
export type TxRow = {
  id: string;
  date: string;
  name: string;
  supplier: string;
  n: string;
  u: string;
  // 원. 가격을 모르면 null
  amount: number | null;
  status: "입고 예정" | "입고 완료" | "취소";
};

const STATUS: Record<MonthHistoryApi["lines"][number]["status"], TxRow["status"]> = {
  SENT: "입고 예정",
  RECEIVED: "입고 완료",
  CANCELLED: "취소",
};

export const toTxRows = (h: MonthHistoryApi): TxRow[] =>
  h.lines.map((l) => ({
    id: l.itemId,
    date: l.date,
    name: l.ingredientName,
    supplier: l.supplierName,
    // 포장 크기를 모르면 양을 낼 수 없다
    ...(l.qty === null ? { n: "–", u: "" } : shownQty(l.qty, l.unit)),
    amount: l.amount,
    status: STATUS[l.status],
  }));

export type IncomingRow = {
  id: string;
  name: string;
  supplier: string;
  pack: string;
  n: string;
  u: string;
};

export const toIncoming = (incoming: IncomingApi[]): IncomingRow[] =>
  incoming
    .filter((x) => x.today)
    .map((x) => {
      const per = Number(x.basePerPack);
      const packs = per > 0 ? Math.round((Number(x.qty) / per) * 10) / 10 : null;
      return {
        id: x.itemId,
        name: x.name,
        supplier: x.supplier,
        pack: x.packLabel && packs !== null ? `${x.packLabel} × ${packs}` : "",
        ...shownQty(x.qty, x.unit),
      };
    });

// 연월 "YYYY-MM" 목록: 기록이 있는 가장 이른 달(없으면 이번 달) ~ 이번 달
export function monthRange(earliest: string | null, today: string): string[] {
  const end = today.slice(0, 7);
  const start = earliest && earliest < end ? earliest : end;
  const out: string[] = [];
  for (
    let [y, m] = start.split("-").map(Number);
    `${y}-${String(m).padStart(2, "0")}` <= end;
    m === 12 ? (y++, (m = 1)) : m++
  )
    out.push(`${y}-${String(m).padStart(2, "0")}`);
  return out;
}
