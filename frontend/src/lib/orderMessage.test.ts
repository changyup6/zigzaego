import assert from "node:assert/strict";
import { test } from "node:test";
import { initOrders, toggleApprove } from "./orderHome.ts";
import {
  buildMessages,
  bySource,
  closePlan,
  itemLine,
  recommendedSource,
  renderMessage,
  setSent,
  sourceOptions,
  topUpLabel,
} from "./orderMessage.ts";
import type { OrderLine, SupplierOrderGroup } from "./types.ts";

const cream = {
  id: "cream",
  name: "생크림",
  unit: "L",
  qty: 3,
  pack: 1,
  packLabel: "생크림 1L",
  unitPrice: 6900,
  msgUnit: "개",
  msgByPack: true,
  baseFactor: 1000,
} as OrderLine;
const fresh = {
  id: "fresh",
  name: "데일리프레시마트",
  minOrder: 30000,
  template: "[앱 주문 목록] {배송일} 배송\n{품목}",
  deliveryDate: "내일",
  arrivalDate: "10/7(수)",
  topUp: [
    { id: "onion", name: "양파", unit: "kg", qty: 2, pack: 1, packLabel: "1 kg", unitPrice: 2800 },
    {
      id: "garlic",
      name: "다진 마늘",
      unit: "kg",
      qty: 1,
      pack: 1,
      packLabel: "1 kg",
      unitPrice: 11000,
    },
  ],
  lines: [cream],
} as SupplierOrderGroup;
const none = { filled: {}, sent: {} };

test("발주한 줄만 거래처 블록이 된다", () => {
  assert.deepEqual(buildMessages([fresh], initOrders([cream]), none), []);
});

test("최소 주문금액 미달 → 보충하면 합계가 다시 계산된다", () => {
  const o = toggleApprove(initOrders([cream]), "cream");
  const [m] = buildMessages([fresh], o, none);
  assert.equal(m.text, "[앱 주문 목록] 10/7(수) 배송\n- 생크림 1L 3개");
  assert.equal(m.total, 20700);
  assert.equal(m.short, 9300);
  assert.equal(topUpLabel(fresh.topUp), "양파 2 kg, 다진 마늘 1 kg 추가");
  const [f] = buildMessages([fresh], o, { filled: { fresh: true }, sent: {} });
  assert.equal(f.total, 20700 + 5600 + 11000);
  assert.equal(f.short, 0);
});

test("메시지 규칙은 백엔드와 같다: 기본 템플릿 · {품목} 없으면 끝에 · 포장 올림 · 포장 모르면 기준 단위", () => {
  assert.equal(
    renderMessage("", "역삼점", "10/31(토)", ["- 대파 1단 2단"]),
    "안녕하세요, 역삼점입니다.\n10/31(토) 배송으로 아래 부탁드립니다.\n- 대파 1단 2단\n감사합니다.",
  );
  assert.equal(renderMessage("{store} 주문", "역삼점", "", ["- a 1팩"]), "역삼점 주문\n- a 1팩");
  assert.equal(
    itemLine({
      name: "대파",
      packLabel: "대파 1단",
      pack: 1,
      qty: 1.6,
      msgUnit: "단",
      msgByPack: true,
      baseFactor: 1000,
    }),
    "- 대파 1단 2단",
  );
  assert.equal(
    itemLine({ name: "소금", packLabel: "", pack: 1, qty: 1.6, msgUnit: "g", baseFactor: 1000 }),
    "- 소금 1,600g",
  );
});

test("주문처: 가격이 있는 곳만 최저 표시, 고른 쇼핑몰 줄은 그 묶음으로 옮긴다", () => {
  const line = {
    ...cream,
    price: {
      history: [],
      sikbom: { price: 6000, stale: false },
      coupang: { price: null, stale: false },
    },
  } as OrderLine;
  const os = sourceOptions(line, fresh, true);
  assert.deepEqual(
    os.map((o) => [o.key, o.price, o.lowest]),
    [
      ["fresh", 6900, false],
      ["sikbom", 6000, true],
      ["coupang", null, false],
    ],
  );
  assert.deepEqual(
    sourceOptions(line, fresh, false).map((o) => o.key),
    ["fresh"],
  );
  const g = bySource([{ ...fresh, lines: [line] }], { cream: "sikbom" });
  assert.deepEqual(
    g.map((x) => [x.id, x.lines[0].unitPrice]),
    [["sikbom", 6000]],
  );
});

test("추천 주문처는 기존 거래처(카톡)를 우선하고 없을 때만 최저가 쇼핑몰을 고른다", () => {
  const o = (key: string, price: number | null, stale = false) => ({
    key,
    name: key,
    price,
    stale,
    lowest: false,
  });
  assert.equal(
    recommendedSource([o("fresh", 6900), o("sikbom", 6500), o("coupang", null)]),
    "fresh",
  );
  assert.equal(recommendedSource([o("fresh", 6900), o("sikbom", 6000, true)]), "fresh");
  assert.equal(recommendedSource([o("fresh", 6900), o("sikbom", 6900)]), "fresh");
  assert.equal(
    recommendedSource([
      { ...o("fresh", null), available: false },
      o("sikbom", 6500),
      o("coupang", 6000),
    ]),
    "coupang",
  );
});

// 닫을 때 저장 (DECISIONS #79): 거래처 2곳에 하나씩 담는다
const leek = { ...cream, id: "leek", name: "대파" } as OrderLine;
const veg = { ...fresh, id: "veg", name: "채소가게", minOrder: 0, lines: [leek] };
const both = toggleApprove(toggleApprove(initOrders([cream, leek]), "cream"), "leek");
const plan = (f: typeof none, saved: string[] = []) => {
  const p = closePlan(buildMessages([fresh, veg], both, f), saved);
  return { save: p.save.map((m) => m.group.id), placed: p.placed };
};

test("아무것도 안 보내고 닫으면 저장 0건 (담은 줄은 그대로)", () => {
  assert.deepEqual(plan(none), { save: [], placed: [] });
});

test("2곳 중 1곳만 보냄 → 그 거래처 줄만 저장, 보충 줄은 뺀다", () => {
  const f = setSent({ filled: { fresh: true }, sent: {} }, "fresh", true);
  assert.deepEqual(plan(f), { save: ["fresh"], placed: ["cream"] });
});

test("보냄 → 취소 → 닫기는 저장 0건", () => {
  const f = setSent(setSent(none, "veg", true), "veg", false);
  assert.deepEqual(plan(f), { save: [], placed: [] });
});

test("저장이 일부 실패한 뒤 다시 닫으면 이미 저장한 거래처는 건너뛴다", () => {
  const f = setSent(setSent(none, "fresh", true), "veg", true);
  assert.deepEqual(plan(f, ["fresh"]), { save: ["veg"], placed: ["cream", "leek"] });
});
