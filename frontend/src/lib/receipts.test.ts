import assert from "node:assert/strict";
import { test } from "node:test";
import { chunkBySize, reading, receiptTitle, reflected } from "./receipts.ts";

test("chunkBySize: 한도를 넘기 전까지 한 요청에 담는다", () => {
  const f = (size: number) => ({ size });
  assert.deepEqual(
    chunkBySize([f(8), f(8), f(8), f(3)], 20).map((c) => c.length),
    [2, 2],
  );
});

test("chunkBySize: 한도보다 큰 장은 혼자 간다 · 빈 목록은 요청 없음", () => {
  assert.deepEqual(
    chunkBySize([{ size: 30 }, { size: 1 }], 20).map((c) => c.length),
    [1, 1],
  );
  assert.deepEqual(chunkBySize([], 20), []);
});

test("reading: 대기 · 인식 중이 있으면 계속 부른다", () => {
  const file = (st: "wait" | "read" | "done" | "fail") => ({ st }) as never;
  assert.equal(reading({ files: [file("done"), file("read")] }), true);
  assert.equal(reading({ files: [file("done"), file("fail")] }), false);
});

test("장 이름: 거래처 (M월 D일), 날짜는 영수증 날짜 → 올린 날", () => {
  const f = { docDate: "2026-11-04", at: "2026-11-06T08:12:00+09:00" };
  assert.equal(receiptTitle(f, { supplierName: "한빛식자재" }), "한빛식자재 (11월 4일)");
  assert.equal(
    receiptTitle({ ...f, docDate: null }, { supplierName: null }),
    "거래처 모름 (11월 6일)",
  );
  assert.equal(receiptTitle(f), "11월 4일");
});

test("반영 완료: 다 읽음 · 확인 전 줄 없음 · 확정만 남은 영수증 아님", () => {
  const f = { id: "1", st: "done" as const };
  const line = (status: "PENDING" | "CONFIRMED" | "IGNORED") => ({ status });
  assert.equal(reflected(f, { lines: [line("CONFIRMED"), line("IGNORED")] } as never, []), true);
  assert.equal(reflected(f, { lines: [line("PENDING")] } as never, []), false);
  assert.equal(reflected(f, { lines: [line("CONFIRMED")] } as never, ["1"]), false);
  assert.equal(reflected({ ...f, st: "read" }, { lines: [] } as never, []), false);
  assert.equal(reflected(f, undefined, []), false);
});
