"use client";

import { useState } from "react";
import { approveAll, initOrders, setQty, toggleApprove } from "@/lib/orderHome";
import type { OrderLine } from "@/lib/types";

// 담기 · 수량은 화면 상태. 저장은 주문 메시지 시트를 닫을 때 보낸 거래처만 한다 (DECISIONS #79, useOrderMessages)
export function useOrderHome(lines: OrderLine[]) {
  const [orders, setOrders] = useState(() => initOrders(lines));
  return {
    orders,
    approve: (id: string) => setOrders((o) => toggleApprove(o, id)),
    changeQty: (id: string, q: number) => setOrders((o) => setQty(o, id, q)),
    // 홈 발주 카드에서 넘어옴: 추천 품목 모두 담기
    fillAll: () =>
      setOrders((o) =>
        approveAll(
          o,
          lines.map((l) => l.id),
        ),
      ),
  };
}
