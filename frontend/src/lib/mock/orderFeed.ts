import type { OrderFeed, OrderLine } from "@/lib/types";
import { DEMO_BUILD } from "../demo.ts";

// ponytail: 스테이징에 시세(market_product) · 거래처가 채워지면 이 파일과 useOrderFeed 의 ?demo 분기를 지운다
// ?demo 로 한 번 들어오면 탭을 옮겨도 목업(창을 닫거나 ?demo=off 면 끝). 홈 · 발주 탭이 같이 본다
export function isDemo() {
  if (!DEMO_BUILD) return false;
  const q = new URLSearchParams(location.search).get("demo");
  try {
    if (q === "off") sessionStorage.removeItem("demo");
    else if (q !== null) sessionStorage.setItem("demo", "1");
    return sessionStorage.getItem("demo") === "1";
  } catch {
    return q !== null && q !== "off";
  }
}

const today =new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(new Date());

const line = (
  id: string,
  name: string,
  qty: number,
  stock: number,
  unitPrice: number,
  history: number[],
  sikbom: number | null,
  coupang: number | null,
): OrderLine => ({
  id,
  name,
  unit: "kg",
  qty,
  stock,
  incoming: 0,
  safety: qty / 4,
  reorderPoint: qty / 2,
  daily: [3, 3, 4, 5, 5, 3, 3].map((d) => (d * qty) / 25),
  arriveLabel: "내일",
  pack: 1,
  packLabel: "1kg",
  unitPrice,
  maxQty: qty * 4,
  timeline: { arriveDay: 1, orderDay: 0 },
  price: {
    history,
    sikbom: {
      price: sikbom,
      stale: false,
      url: `https://www.foodspring.co.kr/search?keyword=${encodeURIComponent(name)}`,
    },
    coupang: {
      price: coupang,
      stale: false,
      url: `https://www.coupang.com/np/search?q=${encodeURIComponent(name)}`,
    },
    sikbomWeekly: sikbom === null ? undefined : history.map((h) => Math.round((h * sikbom) / unitPrice)),
  },
  needsReview: false,
  reasons: [],
});

export const DEMO_ORDER_FEED: OrderFeed = {
  today,
  groups: [
    {
      id: "demo-sup",
      name: "가락 청과",
      channel: "문자",
      cutoff: "21:00",
      minOrder: 0,
      template: "{date} 배송 부탁드립니다.\n{items}",
      deliveryDate: "내일",
      topUp: [],
      lines: [
        line("d1", "양파", 20, 2, 1650, [1500, 1500, 1550, 1600, 1600, 1550, 1600, 1650], 1290, 1480),
        line("d2", "대파", 6, 1, 3900, [3700, 3700, 3750, 3800, 3800, 3750, 3800, 3900], 4100, 3600),
        line("d3", "감자", 10, 3, 2400, [2600, 2550, 2500, 2500, 2450, 2450, 2400, 2400], 2200, null),
      ],
    },
  ],
  fine: [],
  calendar: {},
};
