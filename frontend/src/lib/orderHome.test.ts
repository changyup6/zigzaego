import assert from "node:assert/strict";
import { test } from "node:test";
import {
  approveAll,
  initOrders,
  leftLabel,
  priceView,
  runoutDay,
  setQty,
  summarize,
  toggleApprove,
  toggleHold,
} from "./orderHome.ts";
import type { OrderLine } from "./types.ts";

const line = (id: string, qty: number, unitPrice: number) => ({ id, qty, unitPrice }) as OrderLine;
const lines = [line("a", 25, 4200), line("b", 14, 9800), line("c", 3, 6900)];

test("처음엔 모두 대기, 버튼은 모두 발주", () => {
  const s = summarize(initOrders(lines), lines);
  assert.equal(s.label, "발주 대기");
  assert.equal(s.count, 3);
  assert.equal(s.done, false);
  assert.equal(s.cost, 25 * 4200 + 14 * 9800 + 3 * 6900);
  assert.deepEqual(s.action, { label: "모두 발주", mode: "all" });
});

test("발주하면 메시지 만들기, 보류는 발주를 지운다", () => {
  let o = toggleApprove(initOrders(lines), "a");
  assert.deepEqual(summarize(o, lines).action, { label: "1건 메시지 만들기", mode: "msg" });
  assert.equal(summarize(o, lines).count, 2);
  o = toggleHold(o, "a");
  assert.equal("a" in o.approved, false);
  assert.equal(summarize(o, lines).action.mode, "all");
});

test("모두 발주는 보류를 건너뛰고 완료로 바뀐다", () => {
  const o = approveAll(toggleHold(initOrders(lines), "b"), ["a", "b", "c"]);
  const s = summarize(o, lines);
  assert.equal(s.done, true);
  assert.equal(s.label, "발주 완료");
  assert.equal(s.count, 2);
  assert.equal(s.action.label, "2건 메시지 만들기");
});

test("모두 보류면 버튼이 꺼진다", () => {
  const o = ["a", "b", "c"].reduce(toggleHold, initOrders(lines));
  const s = summarize(o, lines);
  assert.equal(s.label, "모두 보류");
  assert.equal(s.count, 3);
  assert.equal(s.action.mode, null);
});

test("발주한 줄의 수량을 바꾸면 발주 수량 · 예상 금액도 바뀐다", () => {
  const o = setQty(toggleApprove(initOrders(lines), "a"), "a", 30);
  assert.equal(o.approved.a, 30);
  assert.equal(summarize(o, lines).cost, 30 * 4200 + 14 * 9800 + 3 * 6900);
});

const tl = (stock: number, daily: number[], incoming = 0, arriveDay = 1) =>
  ({ stock, daily, incoming, timeline: { arriveDay, orderDay: 0 } }) as OrderLine;

test("소진일: 추정 재고에서 하루씩 빼서 0 이하가 되는 칸 (시안 파스타면 · 닭가슴살)", () => {
  assert.equal(runoutDay(tl(20, [7.8, 8.1, 8.4, 8.6, 9.5, 10.2, 7.5])), 2);
  assert.equal(runoutDay(tl(5.2, [4.4, 4.6, 4.9, 4.7, 5.6, 5.9, 4.1])), 1);
  assert.equal(leftLabel(2), "2일 남음");
});

test("소진일: 입고 예정은 도착일에 더하고, 7일 버티면 일주일 이상", () => {
  assert.equal(runoutDay(tl(2.5, [3, 3.2, 3.3, 3.1, 3.8, 4, 2.8], 12, 0)), 4);
  assert.equal(runoutDay(tl(100, [1, 1, 1, 1, 1, 1, 1])), null);
  assert.equal(runoutDay(tl(0, [0, 0, 0, 0, 0, 0, 0])), null);
  assert.equal(leftLabel(null), "일주일 이상");
  assert.equal(leftLabel(0), "오늘 소진");
});

const market = (price: number | null, stale = false) => ({ price, stale });

test("가격: 앞 7주 평균보다 +12% 면 오름 배지, 막대는 20 ~ 100", () => {
  const v = priceView(
    {
      history: [3700, 3700, 3750, 3800, 3800, 3750, 3800, 4200],
      sikbom: market(3900),
      coupang: market(4350),
    },
    4200,
  );
  assert.equal(v.label, "최근 8주보다 +12%");
  assert.equal(v.change, "up");
  assert.deepEqual(v.bars, [20, 20, 28, 36, 36, 28, 36, 100]);
  assert.deepEqual(
    v.cells.filter((c) => c.lowest).map((c) => c.name),
    ["식봄"],
  );
});

test("가격: ±5% 미만은 비슷, 없는 곳은 최저가에서 빠진다", () => {
  const v = priceView(
    {
      history: [10100, 10000, 9900, 9900, 9800, 9800, 9800, 9800],
      sikbom: market(null),
      coupang: market(9200, true),
    },
    9800,
  );
  assert.equal(v.label, "최근 8주와 비슷");
  assert.equal(v.change, "flat");
  assert.deepEqual(
    v.cells.map((c) => [c.name, c.price, c.stale, c.lowest]),
    [
      ["기존 거래처", 9800, false, false],
      ["식봄", null, false, false],
      ["쿠팡", 9200, true, true],
    ],
  );
});

test("가격: -5% 이상 내리면 내림 배지", () => {
  const v = priceView(
    { history: [100, 100, 100, 100, 100, 100, 100, 90], sikbom: market(95), coupang: market(99) },
    90,
  );
  assert.equal(v.label, "최근 8주보다 -10%");
  assert.equal(v.change, "down");
});

test("가격: 식봄 주별 점은 거래처 막대와 같은 눈금, 없는 주는 null", () => {
  const v = priceView(
    {
      history: [100, 100, 100, 100, 100, 100, 100, 200],
      sikbom: market(50),
      coupang: market(null),
      sikbomWeekly: [null, null, null, null, null, null, 50, 150],
    },
    200,
  );
  assert.deepEqual(v.marks, [null, null, null, null, null, null, 20, 73]);
  assert.equal(v.bars[7], 100);
  assert.equal(priceView({ history: [1], sikbom: market(null), coupang: market(null) }, 1).marks, null);
});
