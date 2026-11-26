import assert from "node:assert/strict";
import { test } from "node:test";
import { analysisPhase, menuLabels } from "./analysis.ts";
import type { Job } from "./types.ts";

const menus = (n: number) =>
  Array.from({ length: n }, (_, i) => ({ id: `m${i}`, name: `m${i}`, ingredientCount: 3 }));

test("progress 0.5, 메뉴 30개: 1~15번째는 '대기'가 아니고 16~30번째는 '대기'", () => {
  const labels = menuLabels(0.5, menus(30), false);
  labels.slice(0, 15).forEach((l) => assert.notEqual(l, "대기"));
  labels.slice(15).forEach((l) => assert.equal(l, "대기"));
});

test("메뉴 30개: 첫 메뉴는 0~3.3%", () => {
  assert.deepEqual(menuLabels(1 / 60, menus(30), false).slice(0, 2), ["정리 중", "대기"]);
  assert.equal(menuLabels(0, menus(30), false)[0], "대기");
});

test("DONE 이면 재료 N개를 한꺼번에", () => {
  assert.deepEqual(menuLabels(1, menus(2), true), ["재료 3개", "재료 3개"]);
});

test("단계 글자", () => {
  const job = (status: Job["status"], progress: number): Job => ({
    id: "j",
    type: "ONBOARDING",
    status,
    progress,
  });
  assert.equal(analysisPhase(job("RUNNING", 0.1), false), "메뉴 가져오는 중");
  assert.equal(analysisPhase(job("RUNNING", 0.32), false), "메뉴 정리 중");
  assert.equal(analysisPhase(job("RUNNING", 0.5), false), "재료 구성 정리 중");
  assert.equal(analysisPhase(job("RUNNING", 0.95), false), "질문 만드는 중");
  assert.equal(analysisPhase(job("DONE", 1), false), "분석 완료");
  assert.equal(analysisPhase(job("FAILED", 0.3), false), "가져오기 실패");
  assert.equal(analysisPhase(job("DONE", 0.3), true), "메뉴 0개");
});
