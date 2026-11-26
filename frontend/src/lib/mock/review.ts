import type { Reviewable } from "@/lib/types";

// 확인 필요 데모(/dev/review) 데이터. 시안 weglee_3.html 의 MATCHES · MENUS · ING · UNMAPPED 를 옮겼다.

// 날짜를 고정해 정적 페이지와 브라우저가 같은 글자를 그린다
export const REVIEW_SHELL = {
  storeName: "역삼점",
  today: "10월 6일 월요일",
};

export type DemoOption = { id: string; name: string };

// 입고 > 확인 목록: 영수증 상품명 → 재료 (+ 단위)
export type DemoMatch = Reviewable & {
  name: string;
  receipts: number;
  guess: string;
  unit?: boolean;
};

// 레시피 > 메뉴 하나: 재료 × 1인분
export type DemoRecipeLine = Reviewable & { name: string; qty: number; unit: string };

// 거래처 및 재료 > 재료
export type DemoIngredient = Reviewable & {
  name: string;
  stock: number;
  unit: string;
  safety: number;
  safetyDays: number;
  max: number;
  price: number;
  product: string;
};

// 더보기 > POS 업로드 > 메뉴 연결
export type DemoUnmapped = Reviewable & { pos: string; guess: string };

const ok: Omit<Reviewable, "id"> = { needsReview: false, reasons: [] };

export const DEMO_INGREDIENT_OPTIONS: DemoOption[] = [
  { id: "noodle", name: "고단백 파스타면" },
  { id: "chicken", name: "닭가슴살" },
  { id: "cream", name: "생크림" },
  { id: "shrimp", name: "칵테일 새우" },
  { id: "parm", name: "파마산 치즈" },
  { id: "garlic", name: "다진 마늘" },
  { id: "perilla", name: "들기름" },
];

export const DEMO_MENU_OPTIONS: DemoOption[] = [
  { id: "alio", name: "치킨 알리오 파스타볼" },
  { id: "perilla", name: "불고기 들기름 파스타볼" },
  { id: "carbo", name: "치킨 까르보나라 파스타볼" },
  { id: "bread", name: "브레드 스틱 2개" },
];

export const DEMO_MATCHES: DemoMatch[] = [
  {
    id: "m1",
    name: "냉동 칵테일새우 1kg",
    receipts: 2,
    guess: "shrimp",
    needsReview: true,
    reasons: ["UNIT_UNSET"],
  },
  {
    id: "m2",
    name: "파마산 치즈 가루",
    receipts: 1,
    guess: "parm",
    unit: true,
    needsReview: true,
    reasons: ["UNIT_UNSET"],
  },
  {
    id: "m3",
    name: "키친타올 6롤",
    receipts: 1,
    guess: "",
    needsReview: true,
    reasons: ["UNIT_UNSET"],
  },
];

export const DEMO_RECIPE: { name: string; price: number; lines: DemoRecipeLine[] } = {
  name: "치킨 알리오 파스타볼",
  price: 9900,
  lines: [
    { id: "noodle", name: "고단백 파스타면", qty: 180, unit: "g", ...ok },
    { id: "chicken", name: "닭가슴살", qty: 120, unit: "g", ...ok },
    {
      id: "garlic",
      name: "다진 마늘",
      qty: 20,
      unit: "g",
      needsReview: true,
      reasons: ["UNANSWERED"],
    },
    { id: "olive", name: "올리브오일", qty: 20, unit: "ml", ...ok },
    { id: "parm", name: "파마산 치즈", qty: 8, unit: "g", ...ok },
    { id: "pepper", name: "페페론치노", qty: 2, unit: "g", ...ok },
  ],
};

export const DEMO_INGREDIENTS: DemoIngredient[] = [
  {
    id: "noodle",
    name: "고단백 파스타면",
    stock: 20,
    unit: "kg",
    safety: 8.6,
    safetyDays: 1,
    max: 60,
    price: 4200,
    product: "한결제면 5 kg 박스",
    ...ok,
  },
  {
    id: "chicken",
    name: "닭가슴살",
    stock: 5.2,
    unit: "kg",
    safety: 4.9,
    safetyDays: 1,
    max: 30,
    price: 9800,
    product: "미트앤씨 축수산 2 kg 팩",
    ...ok,
  },
  {
    id: "garlic",
    name: "다진 마늘",
    stock: 3.5,
    unit: "kg",
    safety: 0.5,
    safetyDays: 1,
    max: 5,
    price: 11000,
    product: "데일리프레시마트 1 kg",
    ...ok,
  },
  {
    id: "perilla",
    name: "들기름",
    stock: 4.2,
    unit: "L",
    safety: 0.4,
    safetyDays: 1,
    max: 6,
    price: 32000,
    product: "데일리프레시마트 1 L",
    needsReview: true,
    reasons: ["NEEDS_COUNT"],
  },
  {
    id: "olive",
    name: "올리브오일",
    stock: 6,
    unit: "L",
    safety: 0.7,
    safetyDays: 1,
    max: 10,
    price: 14000,
    product: "데일리프레시마트 1 L",
    ...ok,
  },
];

export const DEMO_UNMAPPED: DemoUnmapped[] = [
  { id: "u1", pos: "치킨알리오(L)", guess: "alio", needsReview: true, reasons: ["UNMAPPED"] },
  { id: "u2", pos: "브레드스틱 추가", guess: "bread", needsReview: true, reasons: ["UNMAPPED"] },
];
