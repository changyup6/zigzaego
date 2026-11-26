import type {
  InventoryEstimateApi,
  ApiUnit,
  DailyConsumptionApi,
  DeliveryDay,
  IngredientBody,
  IngredientEdit,
  IngredientFullApi,
  IngredientKind,
  IngredientRow,
  RecipeUnit,
  StorageType,
  Supplier,
  SupplierApi,
  SupplierBody,
  SupplierChannel,
  SupplierEdit,
  SupplierProductApi,
  SupplierProductBody,
} from "./types";
import { OTHER } from "./ingredientCategory.ts";
import { defaultPlace } from "./stockPlace.ts";
import { FROM_API } from "./units.ts";

// 안전재고 z (설계 5.3): 냉동 · 상온 97% / 냉장 95% / 신선 90%
export const Z: Record<StorageType, number> = {
  FROZEN: 1.88,
  AMBIENT: 1.88,
  CHILLED: 1.65,
  FRESH: 1.28,
};

export const STORAGE_LABEL: Record<StorageType, string> = {
  FROZEN: "냉동",
  AMBIENT: "상온",
  CHILLED: "냉장",
  FRESH: "신선",
};

export const KIND_LABEL: Record<IngredientKind, string> = {
  BOM: "BOM",
  ONE_TO_ONE: "1:1",
  CYCLE: "주기형",
};

// 화면 단위: DB 는 g · ml · 개, 1,000 이상은 kg · L (설계 3절)
export const shownUnit = (u: RecipeUnit) => (u === "g" ? "kg" : u === "ml" ? "L" : "개");

const r1 = (n: number) => Math.round(n * 10) / 10;

// 안전재고 = 최근 7일 평균 소비 × 일수 (#10 · #52). 일수는 사장님이 고친다.
// ponytail: z × σ × √(R+L) 식(#28)은 소비 이력 28일 이상일 때 BE 가 계산. 화면은 z 만 보인다.
export const safetyStock = (daily: number[], days: number) =>
  r1((daily.reduce((a, b) => a + b, 0) / (daily.length || 1)) * days);

export const priceChange = (price: number, base: number) =>
  base > 0 ? Math.round(((price - base) / base) * 100) : 0;

// 발주 추천 수량은 1회 최대를 넘지 않는다 (3주 계획 8절)
export const capQty = (qty: number, max: number) => Math.min(qty, max);

export const supplierName = (suppliers: { id: string; name: string }[], id: string) =>
  suppliers.find((s) => s.id === id)?.name ?? "";

export const isLocked = (row: Pick<IngredientRow, "unitStatus">) => row.unitStatus === "LOCKED";

export const CHANNEL_LABEL: Record<SupplierChannel, string> = {
  KAKAO: "카톡",
  PHONE: "전화",
  EMAIL: "이메일",
  WEB: "웹",
  MALL: "쇼핑몰",
};

export const DAY_LABEL: Record<DeliveryDay, string> = {
  MON: "월",
  TUE: "화",
  WED: "수",
  THU: "목",
  FRI: "금",
  SAT: "토",
  SUN: "일",
};

export const DAYS = Object.keys(DAY_LABEL) as DeliveryDay[];

export const daysLabel = (days: DeliveryDay[]) => days.map((d) => DAY_LABEL[d]).join(", ");

export const toSupplier = (s: SupplierApi): Supplier => ({
  id: s.id,
  name: s.name,
  channel: s.channel,
  cutoff: s.cutoffTime ?? "",
  leadDays: s.leadTimeDays,
  cycleDays: s.orderCycleDays,
  days: s.deliveryDays ?? [],
  minOrder: s.minOrderAmount ?? 0,
  template: s.messageTemplate ?? "",
});

// 비운 칸은 보내지 않는다 (백엔드는 null 을 '그대로'로 읽는다)
export const supplierBody = (e: SupplierEdit): SupplierBody => ({
  ...(e.name?.trim() && { name: e.name.trim() }),
  ...(e.channel && { channel: e.channel }),
  ...(e.cutoff && { cutoffTime: e.cutoff }),
  ...(e.leadDays != null && { leadTimeDays: e.leadDays }),
  ...(e.cycleDays != null && e.cycleDays > 0 && { orderCycleDays: e.cycleDays }),
  ...(e.days && { deliveryDays: e.days }),
  ...(e.minOrder !== undefined && { minOrderAmount: e.minOrder }),
  ...(e.template !== undefined && { messageTemplate: e.template }),
});

// DB 는 g · ml · 개, 화면은 kg · L · 개 (1,000 단위)
const TO_API: Record<RecipeUnit, ApiUnit> = { g: "G", ml: "ML", 개: "EA" };
export const unitFactor = (u: RecipeUnit) => (u === "개" ? 1 : 1000);
const r3 = (n: number) => Math.round(n * 1000) / 1000;

// 최근 7일 일 소비량 (화면 단위). 판매 없는 날은 0
export function dailyUsage(
  lines: DailyConsumptionApi[],
  dates: string[],
  ingredientId: string,
  unit: RecipeUnit,
) {
  const f = unitFactor(unit);
  return dates.map(
    (d) =>
      lines
        .filter((l) => l.ingredientId === ingredientId && l.date === d)
        .reduce((a, l) => a + Number(l.qty), 0) / f,
  );
}

export function toIngredientRows(
  ingredients: IngredientFullApi[],
  products: SupplierProductApi[],
  consumption: DailyConsumptionApi[],
  dates: string[],
): IngredientRow[] {
  return ingredients.map((i) => {
    const unit = FROM_API[i.unit];
    const f = unitFactor(unit);
    const mine = products.filter((p) => p.ingredientId === i.id);
    const p = mine.find((x) => x.primary) ?? mine[0];
    const bqp = Number(p?.baseQtyPerPack) || 0;
    return {
      id: i.id,
      name: i.name,
      baseUnit: unit,
      unitStatus: i.unitStatus,
      storage: i.storageType,
      kind: i.kind,
      place: i.storageLocation ?? defaultPlace(i.storageType, i.kind),
      placeSaved: !!i.storageLocation,
      category: i.category ?? OTHER,
      stock: null,
      countedAt: null,
      daily: dailyUsage(consumption, dates, i.id, unit),
      safetyDays: Number(i.safetyDays),
      maxQty: i.maxOrderQty == null ? null : Number(i.maxOrderQty) / f,
      productId: p?.id ?? null,
      supplierId: p?.supplierId ?? "",
      packLabel: p?.productName ?? "",
      packQty: bqp / f,
      packUnit: p?.packUnit ?? "",
      supplierPriceKnown: p?.price != null && bqp > 0,
      unitPrice: p?.price != null && bqp > 0 ? (p.price / bqp) * f : 0,
      needsReview: i.needsReview,
      reasons: i.reviewReasons,
    };
  });
}

// 추정 재고(GET /inventory/estimate)를 화면 단위로 싣는다. 실사가 필요하면 확인 필요로 (#9)
export function withStock(rows: IngredientRow[], est: InventoryEstimateApi[]): IngredientRow[] {
  return rows.map((r) => {
    const e = est.find((x) => x.ingredientId === r.id);
    if (!e) return r;
    const reasons: IngredientRow["reasons"] =
      e.needsCount && !r.reasons.includes("NEEDS_COUNT")
        ? [...r.reasons, "NEEDS_COUNT"]
        : r.reasons;
    return {
      ...r,
      stock: e.estimatedQty == null ? null : Number(e.estimatedQty) / unitFactor(r.baseUnit),
      countedAt: e.lastCountedAt,
      needsReview: r.needsReview || e.needsCount,
      reasons,
    };
  });
}

// 바뀐 칸만 보낸다. 입고 기록이 생긴 단위는 보내지 않는다 (백엔드 409)
export function ingredientBody(row: IngredientRow, e: IngredientEdit): IngredientBody {
  const f = unitFactor(e.baseUnit);
  return {
    ...(e.baseUnit !== row.baseUnit && { unit: TO_API[e.baseUnit] }),
    ...(e.kind !== row.kind && { kind: e.kind }),
    ...(e.storage !== row.storage && { storageType: e.storage }),
    ...(e.safetyDays > 0 && e.safetyDays !== row.safetyDays && { safetyDays: e.safetyDays }),
    ...(e.maxQty !== null &&
      e.maxQty > 0 &&
      e.maxQty !== row.maxQty && { maxOrderQty: r3(e.maxQty * f) }),
  };
}

// 상품 · 단가 저장 계획. 같은 거래처 상품이 이미 있으면 고치고, 없으면 새로 만들어 주 상품으로 둔다.
// 거래처는 상품에서 못 바꾸므로 거래처를 바꾸면 그 거래처 상품을 쓴다.
export type ProductPlan =
  | { op: "none" }
  | { op: "patch"; id: string; body: SupplierProductBody }
  | { op: "create"; body: SupplierProductBody };

export function productPlan(
  row: IngredientRow,
  e: IngredientEdit,
  products: SupplierProductApi[],
): ProductPlan {
  const name = e.packLabel.trim();
  if (!e.supplierId || !name) return { op: "none" };
  const f = unitFactor(e.baseUnit);
  const hit = products.find((p) => p.ingredientId === row.id && p.supplierId === e.supplierId);
  if (hit) {
    const bqp = Number(hit.baseQtyPerPack) || f;
    const body: SupplierProductBody = {
      ...(name !== hit.productName && { productName: name }),
      ...(e.unitPrice >= 0 && { price: Math.round((e.unitPrice * bqp) / f) }),
      ...(!hit.primary && { primary: true }),
    };
    return { op: "patch", id: hit.id, body };
  }
  return {
    op: "create",
    body: {
      supplierId: e.supplierId,
      ingredientId: row.id,
      productName: name,
      baseQtyPerPack: f,
      price: Math.max(0, Math.round(e.unitPrice)),
      primary: true,
    },
  };
}

// 단가 글자: 포장을 알면 포장 1개 값 ("20,450원/1망"), 아니면 화면 단위당 ("2,450원/kg"), 모르면 "–"
export const packPriceLabel = (
  r: Pick<IngredientRow, "unitPrice" | "packQty" | "packUnit" | "baseUnit">,
) =>
  r.unitPrice <= 0
    ? "–"
    : r.packQty > 0
      ? `${Math.round(r.unitPrice * r.packQty).toLocaleString("ko-KR")}원/1${r.packUnit || "포장"}`
      : `${r.unitPrice.toLocaleString("ko-KR")}원/${shownUnit(r.baseUnit)}`;
