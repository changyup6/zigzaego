import assert from "node:assert/strict";
import { test } from "node:test";
import { recipeToastText } from "./recipeToast.ts";

test("추정값이 없으면 확인 완료, 있으면 개수", () => {
  assert.equal(recipeToastText(0), "레시피 확인 완료");
  assert.equal(recipeToastText(3), "추정값 3개");
});
