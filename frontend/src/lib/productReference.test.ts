import { test } from "node:test";
import assert from "node:assert/strict";
import { productReference } from "./productReference.ts";

test("상품 참조는 HTTPS 실주소만 허용하고 시연 placeholder와 실행 URL을 제외한다", () => {
  const url = "https://www.foodspring.co.kr/goods/detail/550224?e_id=search_results";
  assert.equal(productReference(url), url);
  for (const value of [
    undefined,
    "",
    "javascript:alert(1)",
    "https://example.invalid/demo",
    "https://user:pass@example.com",
    "http://example.com",
  ]) {
    assert.equal(productReference(value), null);
  }
});
