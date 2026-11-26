import type { SupplierApi, SupplierProductApi } from "./types";

export type DemoStatement = {
  id: string;
  supplierId: string;
  supplierName: string;
  filename: string;
  date: string;
  lines: { id: string; rawName: string; qty: number; unit: string | null; amount: number | null }[];
  total: number | null;
};

const filenamePart = (value: string) =>
  Array.from(value)
    .map((character) => (character.charCodeAt(0) < 32 ? "_" : character))
    .join("")
    .replace(/[<>:"/\\|?*]/g, "_");

export function buildDemoStatements(
  suppliers: Pick<SupplierApi, "id" | "name">[],
  products: Pick<SupplierProductApi, "id" | "supplierId" | "productName" | "packUnit" | "price">[],
  date: string,
): DemoStatement[] {
  return suppliers
    .filter((supplier) => supplier.name.trim())
    .map((supplier) => {
      const lines = products
        .filter((product) => product.supplierId === supplier.id)
        .map((product) => ({
          id: product.id,
          rawName: product.productName,
          qty: 1,
          unit: product.packUnit || null,
          amount:
            product.price != null && Number.isFinite(product.price) && product.price >= 0
              ? product.price
              : null,
        }));
      return {
        id: `statement-${supplier.id}-${date}`,
        supplierId: supplier.id,
        supplierName: supplier.name,
        filename: `${filenamePart(supplier.name)}_거래명세서_${date.replaceAll("-", "")}.pdf`,
        date,
        lines,
        total:
          lines.length && lines.every((line) => line.amount !== null)
            ? lines.reduce((sum, line) => sum + line.amount!, 0)
            : null,
      };
    });
}
