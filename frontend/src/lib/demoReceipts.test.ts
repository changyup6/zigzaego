import { test } from "node:test";
import assert from "node:assert/strict";
import { demoReceiptBoard, demoReceiptPreview, confirmDemoReceipts } from "./demoReceipts.ts";
import type { ReceiptBoard } from "./types";

test("시연 미리보기는 가게 재료와 선택 파일명만 쓰고 서버 문서를 확정하지 않는다", () => {
  const server: ReceiptBoard = {
    jobId: "server-job",
    files: [],
    matches: [],
    readyDocs: [{ docId: "server-doc", kind: "RECEIVE", date: "2026-11-12", lines: 1 }],
    options: [{ id: "tenant-ingredient", name: "가게 재료" }],
  };
  const base = demoReceiptBoard(server);
  assert.equal(base.readyDocs.length, 0);
  const result = demoReceiptPreview(
    base,
    ["아무 영수증.png", "두번째.pdf"],
    "2026-11-12",
    "batch",
    "2026-11-17T00:00:00Z",
  );
  assert.equal(result.board.files.length, 2);
  assert.equal(result.board.files[0].file, "아무 영수증.png");
  assert.equal(result.board.files[0].st, "done");
  assert.equal(result.board.readyDocs.length, 2);
  assert.equal(Object.values(result.details)[0].lines[0].rawName, "가게 재료");
  assert.equal(base.files.length, 0);
  assert.equal(server.readyDocs[0].docId, "server-doc");
  assert.equal(confirmDemoReceipts(result.board).readyDocs.length, 0);
});

test("재료가 아직 없어도 선택한 파일로 시연 성공 결과를 만든다", () => {
  const result = demoReceiptPreview(
    demoReceiptBoard(),
    ["receipt.jpg"],
    "2026-11-12",
    "batch",
    "2026-11-17T00:00:00Z",
  );
  assert.equal(result.board.files[0].st, "done");
  assert.equal(Object.values(result.details)[0].lines[0].rawName, "receipt.jpg");
});
