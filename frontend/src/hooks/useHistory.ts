"use client";

import { useEffect, useState } from "react";
import { getMonthHistory, getReceiving } from "@/lib/api";
import { toIncoming, toTxRows, type IncomingRow, type TxRow } from "@/lib/history";

// 발주 이력 탭: 오늘 입고 예정 = GET /receiving
export function useIncoming() {
  const [data, setData] = useState<IncomingRow[] | null>(null);
  const [error, setError] = useState(false);
  useEffect(() => {
    let live = true;
    getReceiving().then((v) => {
      if (!live) return;
      if (!v.success) return setError(true);
      setData(toIncoming(v.data.incoming));
    });
    return () => {
      live = false;
    };
  }, []);
  return { data, error };
}

type MonthRows = { rows: TxRow[]; total: number; earliest: string | null };

// 지난 발주 한 달치 = GET /purchase-orders/history?month. 한 번 본 달은 다시 부르지 않는다
export function useMonthHistory(month: string) {
  const [months, setMonths] = useState<Record<string, MonthRows>>({});
  const [error, setError] = useState(false);
  const has = month in months;
  useEffect(() => {
    if (has) return;
    let live = true;
    getMonthHistory(month).then((r) => {
      if (!live) return;
      setError(!r.success);
      if (!r.success) return;
      const m = { rows: toTxRows(r.data), total: r.data.totalAmount, earliest: r.data.earliestMonth };
      setMonths((x) => ({ ...x, [month]: m }));
    });
    return () => {
      live = false;
    };
  }, [month, has]);
  // earliestMonth 는 어느 달 응답이나 같다. 새 달을 부르는 동안에도 연월 목록이 그대로 있게 본 달 아무거나에서
  const earliest = Object.values(months)[0]?.earliest ?? null;
  return { data: months[month] ?? null, earliest, error };
}
