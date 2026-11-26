import { test } from "node:test";
import assert from "node:assert/strict";
import { buildDemoStatements } from "./demoStatements.ts";
import { demoReceiptBoard, demoReceiptPreview } from "./demoReceipts.ts";

test("각 거래처에 등록된 상품만 명세서 한 장에 담고 인식 결과에도 거래처를 유지한다", () => {
  const suppliers = [
    { id: "s1", name: "푸른식자재" },
    { id: "s2", name: "새벽청과" },
    { id: "s3", name: "한빛축산" },
  ];
  const products = suppliers.map((s, i) => ({
    id: `p${i}`,
    supplierId: s.id,
    productName: `품목${i}`,
    packUnit: "상자",
    price: (i + 1) * 10000,
  }));
  const documents = buildDemoStatements(
    suppliers,
    [
      ...products,
      {
        id: "foreign",
        supplierId: "foreign",
        productName: "다른 거래처",
        packUnit: "팩",
        price: 900,
      },
    ],
    "2026-11-12",
  );
  assert.equal(documents.length, 3);
  assert.equal(documents[0].filename, "푸른식자재_거래명세서_20261112.pdf");
  assert.equal(documents[1].lines[0].rawName, "품목1");
  assert.equal(documents[2].total, 30000);
  const result = demoReceiptPreview(
    demoReceiptBoard(),
    [documents[2], documents[0], documents[1]],
    "2026-11-12",
    "batch",
    "2026-11-17T00:00:00Z",
  );
  const details = Object.values(result.details);
  assert.deepEqual(
    details.map((d) => d.supplierName),
    ["한빛축산", "푸른식자재", "새벽청과"],
  );
  assert.equal(details[0].lines[0].rawName, "품목2");
  assert.equal(details[0].lines[0].amount, 30000);
  assert.equal(details[0].docDate, documents[2].date);
});

test("가격이 없는 명세서의 합계는 0원으로 만들지 않는다", () => {
  const [document] = buildDemoStatements(
    [{ id: "s", name: "거래처" }],
    [{ id: "p", supplierId: "s", productName: "품목", packUnit: null, price: null }],
    "2026-11-12",
  );
  assert.equal(document.total, null);
  assert.equal(document.lines[0].amount, null);
});
