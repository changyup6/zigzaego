import { MARKET_SOURCES } from "./marketComparison.ts";
import { fmt } from "./format.ts";
import type { Orders } from "./orderHome";
import type { OrderLine, SupplierOrderGroup, TopUpLine } from "./types";

// 주문 메시지 시트 (시안 openMessages · saveMessages). 발주한 줄을 거래처별로 묶는다.
// filled = 보충 버튼을 누른 거래처, sent = 보냄(쇼핑몰은 주문 완료)을 누른 거래처. 취소하면 false (DECISIONS #79)
export type MessageFlags = { filled: Record<string, true>; sent: Record<string, boolean> };

type MsgItem = Pick<TopUpLine, "id" | "name" | "unit" | "pack" | "packLabel" | "unitPrice"> & {
  qty: number;
  topUp: boolean;
};

export type SupplierMessage = {
  group: SupplierOrderGroup;
  items: MsgItem[];
  text: string;
  total: number;
  // 최소 주문금액까지 모자란 돈. 0 이면 충족
  short: number;
  sent: boolean;
  filled: boolean;
};

const DEFAULT_TEMPLATE =
  "안녕하세요, {가게}입니다.\n{배송일} 배송으로 아래 부탁드립니다.\n{품목}\n감사합니다.";
const PLACEHOLDER = /\{(가게|store|배송일|arrival_date|품목|items)\}/g;
const comma = (n: number) => (Math.round(n * 1000) / 1000).toLocaleString("ko-KR");

// 백엔드 OrderMessageRenderer 와 같은 규칙: 자리표시자는 한 번에 바꾸고, {품목} 이 없으면 끝에 붙인다
export function renderMessage(template: string, store: string, arrival: string, items: string[]) {
  const values: Record<string, string> = {
    가게: store,
    store,
    배송일: arrival,
    arrival_date: arrival,
    품목: items.join("\n"),
    items: items.join("\n"),
  };
  let hasItems = false;
  const out = (template.trim() ? template : DEFAULT_TEMPLATE).replace(
    PLACEHOLDER,
    (_, k: string) => {
      hasItems ||= k === "품목" || k === "items";
      return values[k];
    },
  );
  return hasItems ? out : `${out.trimEnd()}\n${values["품목"]}`;
}

// 품목 줄: 포장을 알면 '상품명 포장 수(올림)포장 이름', 모르면 기준 단위 수량
export function itemLine(
  x: Pick<MsgItem, "name" | "packLabel" | "pack" | "qty"> & {
    msgUnit?: string;
    msgByPack?: boolean;
    baseFactor?: number;
  },
) {
  return x.msgByPack
    ? `- ${x.packLabel} ${comma(Math.ceil(x.qty / x.pack - 1e-9))}${x.msgUnit}`
    : `- ${x.packLabel || x.name} ${comma(x.qty * (x.baseFactor ?? 1))}${x.msgUnit ?? ""}`;
}

export function buildMessages(
  groups: SupplierOrderGroup[],
  orders: Orders,
  f: MessageFlags,
  store = "",
): SupplierMessage[] {
  return groups.flatMap((g) => {
    const items: MsgItem[] = g.lines
      .filter((l) => l.id in orders.approved)
      .map((l) => ({ ...l, qty: orders.approved[l.id], topUp: false }));
    if (!items.length) return [];
    if (f.filled[g.id]) items.push(...g.topUp.map((t) => ({ ...t, topUp: true })));
    const total = items.reduce((s, x) => s + x.qty * x.unitPrice, 0);
    const lines = items.map((x) => itemLine({ ...g.lines.find((l) => l.id === x.id), ...x }));
    return [
      {
        group: g,
        items,
        text: renderMessage(g.template, store, g.arrivalDate ?? g.deliveryDate, lines),
        total,
        short: Math.max(0, g.minOrder - total),
        sent: !!f.sent[g.id],
        filled: !!f.filled[g.id],
      },
    ];
  });
}

// 보냄 · 취소 (DECISIONS #79). 저장은 시트를 닫을 때 (closePlan)
export const setSent = (f: MessageFlags, id: string, on: boolean): MessageFlags => ({
  ...f,
  sent: { ...f.sent, [id]: on },
});

// 발주로 저장하는 줄 = 보충 줄을 뺀 추천 줄
export const lineIds = (m: SupplierMessage) => m.items.filter((x) => !x.topUp).map((x) => x.id);

// 시트를 닫을 때 (DECISIONS #79): 보냄인 거래처만 저장하고 이미 저장한 거래처(saved)는 건너뛴다.
// 저장한 · 저장할 거래처의 줄은 발주, 안 보낸 줄은 담은 채로 둔다 (장바구니)
export function closePlan(messages: SupplierMessage[], saved: string[]) {
  const done = messages.filter((m) => m.sent || saved.includes(m.group.id));
  return {
    save: done.filter((m) => !saved.includes(m.group.id)),
    placed: done.flatMap(lineIds),
  };
}

// 보충 버튼 글자: '양파 2 kg, 다진 마늘 1 kg 추가'
export const topUpLabel = (t: TopUpLine[]) =>
  `${t.map((x) => `${x.name} ${fmt(x.qty)} ${x.unit}`).join(", ")} 추가`;

// 주문처 선택 (DECISIONS #64). 기존 거래처 · 식봄 · 쿠팡. 식봄 · 쿠팡 가격이 없으면 고를 수 없다
export const MARKETS = MARKET_SOURCES;

export type SourceOption = {
  key: string;
  name: string;
  // 화면 단위당 원. null = 팔지 않음
  price: number | null;
  stale: boolean;
  on?: string;
  lowest: boolean;
  available?: boolean;
  cost?: number;
  packQty?: number;
  packPrice?: number;
  productName?: string;
  spec?: string;
  synthetic?: boolean;
  url?: string;
};

export function sourceOptions(
  line: Pick<OrderLine, "unitPrice" | "price" | "supplierPriceKnown" | "pack">,
  supplier: { id: string; name: string },
  compare: boolean,
  qty = 1,
): SourceOption[] {
  const markets = (
    line.price?.markets ?? [
      {
        key: "sikbom",
        name: "식봄",
        ...line.price?.sikbom,
        price: line.price?.sikbom.price ?? null,
        stale: line.price?.sikbom.stale ?? false,
        available: true,
      },
      {
        key: "coupang",
        name: "쿠팡",
        ...line.price?.coupang,
        price: line.price?.coupang.price ?? null,
        stale: line.price?.coupang.stale ?? false,
        available: true,
      },
    ]
  ).map((m) => ({
    ...m,
    price: m.price === null ? null : Math.round(m.price * (line.pack || 1)) / (line.pack || 1),
  }));
  const os = [
    {
      key: supplier.id,
      name: supplier.name,
      price: line.supplierPriceKnown === false ? null : line.unitPrice,
      stale: false,
      available: true,
    },
    ...(compare ? markets : []),
  ];
  const priced = os.filter((o) => o.price !== null && !o.stale && o.available !== false);
  const low = Math.min(...priced.map((o) => o.price!));
  return os.map((o) => ({
    ...o,
    cost: o.price === null ? undefined : o.price * qty,
    lowest: priced.length > 1 && !o.stale && o.available !== false && o.price === low,
  }));
}

export function recommendedSource(os: SourceOption[]): string {
  const primary = os[0];
  if (primary && !primary.stale && primary.available !== false) {
    return primary.key;
  }
  const fresh = os.filter((o) => o.price !== null && !o.stale && o.available !== false);
  return fresh.reduce((a, b) => (b.price! < a.price! ? b : a), fresh[0] ?? os[0]).key;
}

export function resolvedSource(
  line: OrderLine,
  g: SupplierOrderGroup,
  compare: boolean,
  key?: string,
) {
  const os = sourceOptions(line, g, compare);
  return (
    os.find((o) => o.key === key && o.price !== null && !o.stale && o.available !== false) ??
    os.find((o) => o.key === recommendedSource(os))!
  );
}

export function comparisonRows(
  groups: SupplierOrderGroup[],
  compare: boolean,
  qty: Record<string, number> = {},
  src: Record<string, string> = {},
) {
  const rows = groups.flatMap((g) =>
    g.lines.map((line) => {
      const amount = qty[line.id] ?? line.qty;
      const o = resolvedSource(line, g, compare, src[line.id]);
      return {
        line,
        qty: amount,
        src: o.name,
        cost: o.price === null ? null : o.price * amount,
        base: line.supplierPriceKnown === false ? null : line.unitPrice * amount,
      };
    }),
  );
  const total = rows.reduce((s, r) => s + (r.cost ?? 0), 0);
  return {
    rows,
    total,
    saved: rows.some((r) => r.base === null || r.cost === null)
      ? 0
      : rows.reduce((s, r) => s + r.base!, 0) - total,
  };
}

export function bySource(
  groups: SupplierOrderGroup[],
  src: Record<string, string>,
): SupplierOrderGroup[] {
  const out = new Map<string, SupplierOrderGroup>();
  for (const g of groups)
    for (const l of g.lines) {
      // 없는/오래된 주문처는 조용히 잘못 저장하지 않고 원래 거래처로 돌린다.
      const o =
        sourceOptions(l, g, true).find(
          (o) =>
            o.key === (src[l.id] ?? g.id) && o.price !== null && !o.stale && o.available !== false,
        ) ?? sourceOptions(l, g, false)[0];
      if (!out.has(o.key))
        out.set(
          o.key,
          o.key === g.id
            ? { ...g, lines: [] }
            : {
                id: o.key,
                name: o.name,
                channel: "쇼핑몰",
                cutoff: "",
                minOrder: 0,
                template: "",
                deliveryDate: "",
                topUp: [],
                lines: [],
              },
        );
      out.get(o.key)!.lines.push({ ...l, unitPrice: o.price! });
    }
  return [...out.values()];
}

export const isMarket = (id: string) =>
  MARKETS.some((m) => m.id === id) || id.startsWith("market:");
