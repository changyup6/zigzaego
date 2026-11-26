import type { Page } from "@playwright/test";
import { mock } from "./refinements.fixture";

export const SUPPLIERS = ["푸른식자재", "새벽청과", "한빛축산"];
export async function tabletDocuments(page: Page) {
  await mock(page);
  await page.route("**/stores/me/demo-preparation", (route) =>
    route.fulfill({ json: { success: true, data: { eligible: true }, error: null } }),
  );
  await page.route("**/suppliers", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: SUPPLIERS.map((name, index) => ({ id: `s${index}`, name })),
        error: null,
      },
    }),
  );
  await page.route("**/supplier-products", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: SUPPLIERS.map((name, index) => ({
          id: `p${index}`,
          supplierId: `s${index}`,
          ingredientId: "i0",
          productName: `${name} 등록 품목`,
          baseQtyPerPack: 1000,
          packUnit: "팩",
          price: (index + 1) * 10000,
          primary: true,
        })),
        error: null,
      },
    }),
  );
}
