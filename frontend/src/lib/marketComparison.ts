import type { IngredientOffersApi, MarketPriceHistoryApi, MarketCandidate } from "./types";

export const MARKET_SOURCES = [
  { id: "sikbom", name: "식봄" },
  { id: "coupang", name: "쿠팡" },
  { id: "marketbom", name: "마켓봄" },
  { id: "orderlink", name: "오더링크" },
] as const;
const DAY = 864e5;
const day = (date: string) => Date.parse(`${date}T00:00:00Z`);
const shift = (date: string, n: number) => new Date(day(date) + n * DAY).toISOString().slice(0, 10);
const valid = (v: number) => Number.isFinite(v) && v >= 0;

// PriceHistoryService: 7일 칸, 마지막 = StoreClock today−6 .. today. 날짜는 기기 시계에서 가져오지 않는다.
export function weekBuckets(today: string, count = 8) {
  return Array.from({ length: count }, (_, i) => ({
    from: shift(today, -7 * (count - 1 - i) - 6),
    to: shift(today, -7 * (count - 1 - i)),
  }));
}
export function weekLabel(bucket: { from: string; to: string }) {
  const date = bucket.to;
  const words = ["첫째", "둘째", "셋째", "넷째", "다섯째"];
  return `${Number(date.slice(5, 7))}월 ${words[Math.floor((Number(date.slice(8, 10)) - 1) / 7)]} 주`;
}
export function marketCandidates(
  o: IngredientOffersApi | undefined,
  h: MarketPriceHistoryApi | undefined,
  f: number,
  today: string,
): MarketCandidate[] {
  const factor = Number(o?.toIngredientUnitFactor);
  if (o?.toIngredientUnitFactor == null || !Number.isFinite(factor) || factor <= 0) return [];
  return (o?.sites ?? []).flatMap((site) => {
    const raw = Number(site.bestUnitPrice);
    const price = raw * factor * f;
    if (!valid(price)) return [];
    const offer = site.offers[0];
    const packPrice = offer?.price;
    const packQty = packPrice != null && price > 0 ? packPrice / price : undefined;
    const hf = Number(h?.toIngredientUnitFactor);
    const series = h?.sites.find((x) => x.site === site.site);
    const weekly: (number | null)[] = Array(8).fill(null);
    if (h?.toIngredientUnitFactor != null && hf > 0 && Number.isFinite(hf))
      for (const p of series?.points ?? []) {
        const age = (day(today) - day(p.date)) / DAY;
        const i = 7 - Math.floor(age / 7);
        const value = Number(p.unitPrice) * hf * f;
        if (age < 0 || i < 0 || i > 7 || !valid(value)) continue;
        weekly[i] = weekly[i] === null ? value : Math.min(weekly[i]!, value);
      }
    const key = MARKET_SOURCES.find((x) => x.name === site.site)?.id ?? `market:${site.site}`;
    return [
      {
        key,
        name: site.site,
        price,
        stale: site.collectedOn !== today,
        on: site.collectedOn,
        available: site.delivery === "AVAILABLE" && MARKET_SOURCES.some((m) => m.id === key),
        delivery: site.delivery,
        url: offer?.url,
        packPrice,
        packQty,
        productName: offer?.productName,
        spec: offer?.spec ?? undefined,
        synthetic: site.synthetic,
        weekly,
      },
    ];
  });
}
export function chartScale(values: (number | null)[]) {
  const validValues = values.filter((v): v is number => v !== null && valid(v));
  const lo = validValues.length ? Math.min(...validValues) : 0;
  const hi = validValues.length ? Math.max(...validValues) : 0;
  const pad = Math.max((hi - lo) * 0.15, Math.abs(hi) * 0.02, 1);
  return { min: Math.max(0, lo - pad), max: hi + pad };
}
export function chartPath(values: (number | null)[], scale: { min: number; max: number }) {
  return values
    .map((v, i) =>
      v === null || !valid(v)
        ? ""
        : `${i && values[i - 1] !== null ? "L" : "M"}${values.length > 1 ? (i * 100) / (values.length - 1) : 50},${100 - ((v - scale.min) * 100) / (scale.max - scale.min)}`,
    )
    .join(" ");
}
