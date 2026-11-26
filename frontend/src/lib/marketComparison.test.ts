import assert from "node:assert/strict";
import { test } from "node:test";
import {
  marketCandidates,
  weekBuckets,
  weekLabel,
  chartScale,
  chartPath,
} from "./marketComparison.ts";
import { sourceOptions, recommendedSource, bySource, comparisonRows } from "./orderMessage.ts";
import type { OrderLine, SupplierOrderGroup } from "./types.ts";

const offers = {
  toIngredientUnitFactor: 1,
  sites: [
    {
      site: "식봄",
      bestUnitPrice: 4,
      collectedOn: "2026-11-01",
      delivery: "AVAILABLE",
      offers: [{ price: 8000, unitPrice: 4, productName: "2kg", url: "s" }],
    },
    {
      site: "쿠팡",
      bestUnitPrice: 3,
      collectedOn: "2026-11-01",
      delivery: "AVAILABLE",
      offers: [{ price: 3000, unitPrice: 3, productName: "1kg", url: "c" }],
    },
    {
      site: "마켓봄",
      bestUnitPrice: 2,
      collectedOn: "2026-10-31",
      delivery: "AVAILABLE",
      offers: [{ price: 2000, unitPrice: 2, url: "m" }],
    },
    {
      site: "오더링크",
      bestUnitPrice: 1,
      collectedOn: "2026-11-01",
      delivery: "UNKNOWN",
      offers: [{ price: 1000, unitPrice: 1, url: "o" }],
    },
  ],
};
test("모든 실제 후보의 포장·배송·신선도를 보존하고 미래·누락 관측은 채우지 않는다", () => {
  const cs = marketCandidates(
    offers,
    {
      toIngredientUnitFactor: 1,
      sites: [
        {
          site: "쿠팡",
          points: [
            { date: "2026-11-01", unitPrice: 3 },
            { date: "2026-11-02", unitPrice: 1 },
            { date: "2026-10-25", unitPrice: 4 },
          ],
        },
      ],
    },
    1000,
    "2026-11-01",
  );
  assert.deepEqual(
    cs.map((c) => c.key),
    ["sikbom", "coupang", "marketbom", "orderlink"],
  );
  assert.equal(cs[0].packQty, 2);
  assert.equal(cs[0].packPrice, 8000);
  assert.equal(cs[2].stale, true);
  assert.equal(cs[3].available, false);
  assert.deepEqual(cs[1].weekly, [null, null, null, null, null, null, 4000, 3000]);
});
test("환산 불가·음수·NaN 가격은 비교하지 않으며 0원은 실제 가격이다", () => {
  assert.deepEqual(
    marketCandidates({ ...offers, toIngredientUnitFactor: null }, undefined, 1000, "2026-11-01"),
    [],
  );
  const cs = marketCandidates(
    {
      ...offers,
      sites: [
        { ...offers.sites[0], bestUnitPrice: -1 },
        { ...offers.sites[1], bestUnitPrice: 0, offers: [{ price: 0, unitPrice: 0, url: "free" }] },
      ],
    },
    undefined,
    1000,
    "2026-11-01",
  );
  assert.equal(cs.length, 1);
  assert.equal(cs[0].price, 0);
});
const line = () =>
  ({
    id: "r",
    qty: 3,
    unitPrice: 5000,
    pack: 1,
    unit: "kg",
    price: {
      history: [null, 5000],
      sikbom: { price: null, stale: false },
      coupang: { price: null, stale: false },
      markets: marketCandidates(offers, undefined, 1000, "2026-11-01"),
    },
  }) as OrderLine;
const group = () =>
  ({
    id: "supplier",
    name: "거래처",
    channel: "",
    cutoff: "",
    minOrder: 0,
    template: "",
    deliveryDate: "",
    lines: [line()],
    topUp: [],
  }) as SupplierOrderGroup;
test("동일 수량의 기준 수량 환산 합계와 최저가 표시를 계산하며 기본 추천은 기존 거래처다", () => {
  const os = sourceOptions(line(), group(), true, 3);
  assert.equal(recommendedSource(os), "supplier");
  assert.equal(os.find((o) => o.key === "coupang")?.lowest, true);
  assert.equal(os.find((o) => o.key === "sikbom")?.cost, 12000);
  assert.equal(os.find((o) => o.key === "marketbom")?.lowest, false);
  assert.equal(os.find((o) => o.key === "orderlink")?.available, false);
  const r = comparisonRows([group()], true, { r: 3 }, { r: "sikbom" });
  assert.equal(r.total, 12000);
  assert.equal(r.saved, 3000);
  assert.equal(comparisonRows([group()], false, { r: 3 }, { r: "sikbom" }).saved, 0);
  assert.equal(bySource([group()], { r: "marketbom" })[0].id, "supplier");
});
test("날짜 구간은 가게 today에서 계산하고 월·연도 경계를 보존한다", () => {
  const bs = weekBuckets("2027-01-03", 8);
  assert.deepEqual(bs.at(-1), { from: "2026-12-28", to: "2027-01-03" });
  assert.equal(weekLabel({ from: "2026-11-01", to: "2026-11-07" }), "11월 첫째 주");
  assert.equal(bs[0].from, "2026-11-09");
});
test("0·한 점·동일 값·누락에 유한 축을 만들며 빈 칸을 가로질러 연결하지 않는다", () => {
  for (const vs of [[], [0], [4, 4], [null, 4, null]]) {
    const s = chartScale(vs);
    assert.ok(Number.isFinite(s.min) && Number.isFinite(s.max) && s.max > s.min);
  }
  assert.equal((chartPath([1, null, 3], chartScale([1, 3])).match(/M/g) || []).length, 2);
  assert.ok(!chartPath([0, 0], chartScale([0, 0])).match(/NaN|Infinity/));
});

test("서로 다른 가게·일자·추천의 초안 키는 공유하지 않는다", async () => {
  const { draftKey } = await import("./orderDraft.ts");
  assert.notEqual(draftKey("a", "2026-11-01", ["r"]), draftKey("b", "2026-11-01", ["r"]));
  assert.notEqual(draftKey("a", "2026-11-01", ["r"]), draftKey("a", "2026-11-02", ["r"]));
});

test("거래처 가격 미설정은 0원 절감으로 만들지 않는다", () => {
  const l = { ...line(), supplierPriceKnown: false, price: null };
  const g = { ...group(), lines: [l] };
  const r = comparisonRows([g], true);
  assert.equal(r.rows[0].cost, null);
  assert.equal(r.saved, 0);
});

test("기존 포장당 원 반올림을 공유하여 주문 저장 환산액과 일치한다", () => {
  const l = {
    ...line(),
    pack: 3,
    price: {
      ...line().price!,
      markets: [{ key: "coupang", name: "쿠팡", price: 3333.3337, stale: false, available: true }],
    },
  };
  const g = { ...group(), lines: [l] };
  assert.equal(comparisonRows([g], true, { r: 6 }, { r: "coupang" }).total, 20000);
});
