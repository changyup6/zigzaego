import { shownQty } from "./units.ts";
import type { IncomingApi } from "./types";

type IncomingItem = Pick<IncomingApi, "itemId" | "name" | "supplier" | "qty" | "unit" | "today">;

export function homeIncoming(items: IncomingItem[]) {
  return items
    .filter((item) => item.today)
    .map((item) => ({
      id: item.itemId,
      name: item.name,
      supplier: item.supplier,
      ...shownQty(item.qty, item.unit),
    }));
}
