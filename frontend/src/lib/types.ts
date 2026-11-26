import type { Place } from "./stockPlace";
// 응답 래퍼 (DECISIONS #22). API 연동 때도 이 모양 그대로 받는다.
export type ApiError = {
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
};

export type ApiResponse<T> =
  { success: true; data: T; error: null } | { success: false; data: null; error: ApiError };

// 화면 공용 한 줄. 목록 화면이 채워지기 전까지 모든 화면이 이 모양으로 항목을 보인다.
export type Item = { id: string; name: string; meta: string };

// 더보기 (DECISIONS #47). 가게 설정이 모두 끝나면 setup 카드 · 홈 띠가 사라진다 (#50)
export type SetupStep = { id: string; name: string; done: boolean; href: string; where?: string };

// GET /stores/me. 체크리스트 순서는 서버 고정 (ONBOARDING · RECIPE · POS · SUPPLIER · FIRST_RECEIVE · STOCK_COUNT)
export type StoreMe = {
  id: string;
  name: string;
  displayName: string;
  // 가게의 오늘 (YYYY-MM-DD). 화면은 기기 날짜 대신 이 값을 쓴다
  today?: string;
  // 시군구 "서울 중구". 아직 안 받았으면 null
  sigungu: string | null;
  // 선택한 네이버 플레이스의 전체 주소. 이전 API/주소 미확인 가게와 호환
  address?: string | null;
  remainingSteps: number;
  checklist: { step: string; done: boolean; remaining: number }[];
  // 가격 비교 켬/끔 (#48). 끄면 식자재몰 최저가를 부르지도 보이지도 않는다
  priceCompare?: boolean;
  // 요일별 마감 시각 (#88). 줄이 없는 요일은 휴무 또는 미설정
  closingTimes?: ClosingTime[];
};

// dayOfWeek 는 월 = 1 … 일 = 7, closeTime 은 가게 시간대 HH:mm
export type ClosingTime = { dayOfWeek: number; closeTime: string };

// 탭 배지: 확인 필요 항목 개수 (설계 6절)
export type ReviewCounts = {
  order: number;
  receiving: number;
  recipes: number;
  suppliers: number;
  more: number;
};

// 확인 필요 (설계 6절): BE 가 행마다 needsReview 와 이유 코드를 싣는다
export type ReviewReason =
  "UNANSWERED" | "LOW_CONFIDENCE" | "UNIT_UNSET" | "UNMAPPED" | "NEEDS_COUNT";

export type Reviewable = { id: string; needsReview: boolean; reasons: ReviewReason[] };

// 비동기 작업 (설계 4.2). 화면이 2초마다 GET /jobs/{id}
export type JobStatus = "QUEUED" | "RUNNING" | "DONE" | "FAILED";

export type Job = {
  id: string;
  type: "ONBOARDING" | "RECEIPT";
  status: JobStatus;
  progress: number;
  result?: unknown;
  error?: string | null;
};

// 온보딩 job 결과 (설계 2.4 · 2.5): 메뉴 이름은 수집이 끝나면, 재료 개수는 DONE 에서 온다
export type AnalysisMenu = { id: string; name: string; ingredientCount?: number };

export type AnalysisResult = { menus: AnalysisMenu[]; needsMenuPhoto?: boolean };

// 가게 찾기 (설계 2.1): GET /onboarding/places 후보, POST /onboarding/jobs 요청
export type PlaceCandidate = {
  id: string;
  name: string;
  address: string;
  photoUrl: string | null;
  yogiyoMatch: boolean;
};

export type OnboardingJobRequest = {
  placeId?: string;
  naverMapUrl?: string;
  includeYogiyo: boolean;
  // 고른 후보의 가게 이름. 서버가 가게 이름 · 화면 이름으로 쓴다 (홈 인사말)
  placeName?: string;
};

export type Tokens = { accessToken: string; refreshToken: string };

// POST /auth/login · 가입 요청 바디
export type Credentials = { email: string; password: string };

// 레시피 확인 (설계 2.4 · 2.6): 구성요소(재료) 1인분 + 메뉴는 구성요소를 배수로 참조한다
export type RecipeUnit = "g" | "ml" | "개";

export type RecipeIngredient = {
  id: string;
  name: string;
  unit: RecipeUnit;
  perServing: number;
  // 답하지 않은 추정값 = 확인 필요 (UNANSWERED)
  estimated: boolean;
};

export type RecipeMenu = {
  id: string;
  name: string;
  price: number;
  // 최근 판매 수 (홈 추천이 재료 소비량을 다시 셀 때의 가중치)
  sold: number;
  // 수집해 저장한 메뉴 사진 (DECISIONS #45). 없으면 자리 표시
  photo?: string;
  lines: { ingredientId: string; mult: number }[];
};

// 레시피 탭 '재료 추가' 시트: 기존 재료를 고르면 단위는 그 재료 단위로 고정 (DECISIONS #33)
export type RecipeLineDraft = { qty: number } & (
  { ingredientId: string } | { name: string; unit: RecipeUnit }
);

export type RecipeBook = { menus: RecipeMenu[]; ingredients: RecipeIngredient[] };

// 레시피 탭 (GET /menus · /recipes · /ingredients). 레시피 줄마다 1인분 양을 따로 가진다.
export type RecipeLine = {
  id: string;
  ingredientId: string;
  name: string;
  unit: RecipeUnit;
  qty: number;
  estimated: boolean;
};
export type RecipeTabMenu = {
  id: string;
  name: string;
  price: number | null;
  photoUrl: string | null;
  needsReview: boolean;
  lines: RecipeLine[];
};
export type RecipePick = { id: string; name: string; unit: RecipeUnit };
export type RecipeTab = { menus: RecipeTabMenu[]; ingredients: RecipePick[] };

// 백엔드 응답 (숫자 칸은 문자열로 올 수도 있어 Number 로 읽는다)
export type ApiUnit = "EA" | "G" | "ML";
export type MenuApi = {
  id: string;
  name: string;
  price: number | null;
  // 이전 백엔드 응답과도 호환한다.
  photoUrl?: string | null;
  needsReview: boolean;
};
export type RecipeApi = {
  id: string;
  menuId: string;
  ingredientId: string;
  ingredientName: string;
  unit: ApiUnit;
  qtyPerServing: number | string;
  estimated: boolean;
};
export type IngredientApi = { id: string; name: string; unit: ApiUnit; kind: string };

// 시안 #question: 확신이 낮은 구성요소만 묻는다 (설계 2.6). 보기 3개(낮은 순) + 직접 입력.
// ingredientId 는 분석 결과 안의 재료 id (DB id 아님). GET /onboarding/recipe-questions
export type RecipeQuestion = {
  id: string;
  ingredientId: string;
  text: string;
  unit: RecipeUnit;
  options: [number, number, number];
};

// 구성요소 1인분 (같은 재료를 쓰는 메뉴 전체). POST /onboarding/recipes/confirm
export type RecipeAnswer = { questionId: string; value: number; unit: RecipeUnit };

// 발주 홈 (3주 계획 6절 1번). 발주 계산은 백엔드. 화면은 받은 재고 · 예상 소비로 타임라인과 남은 날을 그린다.
// 요일 칸 인덱스: 0 = 오늘 … 6 = 엿새 뒤
export type OrderTimeline = {
  arriveDay: number;
  orderDay: number | null;
};

// 식봄 · 쿠팡 단가 (설계 5.5). price null = 팔지 않음, stale = 오늘 수집 값이 아님(on = 수집일 M/d)
export type MarketPrice = { price: number | null; stale: boolean; url?: string; on?: string };

export type MarketCandidate = MarketPrice & {
  key: string;
  name: string;
  available: boolean;
  delivery?: string;
  packQty?: number;
  packPrice?: number;
  productName?: string;
  spec?: string;
  synthetic?: boolean;
  weekly?: (number | null)[];
};

// 원/기본 단위. history 는 최근 8주 거래처 단가, 마지막 칸이 이번 주. sikbomWeekly 는 같은 칸의 식봄 최저가
export type PriceHistory = {
  history: (number | null)[];
  buckets?: { from: string; to: string }[];
  markets?: MarketCandidate[];
  sikbom: MarketPrice;
  coupang: MarketPrice;
  sikbomWeekly?: (number | null)[];
};

// needsReview 는 이름 옆 글자로만 보인다. 거래처 묶음 안 순서는 그대로 (DECISIONS #44)
export type OrderLine = Reviewable & {
  name: string;
  unit: string;
  qty: number;
  stock: number;
  incoming: number;
  safety: number;
  reorderPoint: number;
  // 오늘부터 7일 예상 소비
  daily: number[];
  arriveLabel: string;
  pack: number;
  packLabel: string;
  unitPrice: number;
  supplierPriceKnown?: boolean;
  maxQty: number;
  timeline: OrderTimeline;
  price: PriceHistory | null;
  // 메시지 품목 줄 (백엔드 OrderMessageRenderer 와 같은 표기). 포장을 모르면 기준 단위
  msgUnit?: string;
  msgByPack?: boolean;
  baseFactor?: number;
};

// 최소 주문금액 보충 줄 (시안 '양파 2 kg, 마늘 1 kg 추가'). qty 는 기본 단위
export type TopUpLine = Pick<
  OrderLine,
  "id" | "name" | "unit" | "pack" | "packLabel" | "unitPrice"
> & {
  qty: number;
};

// template 의 {date} = deliveryDate, {items} = 품목 줄 (3주 계획 4.7). minOrder 0 = 없음
export type SupplierOrderGroup = {
  id: string;
  name: string;
  channel: string;
  cutoff: string;
  minOrder: number;
  template: string;
  deliveryDate: string;
  // 메시지 {배송일}: M/d(요일)
  arrivalDate?: string;
  topUp: TopUpLine[];
  lines: OrderLine[];
};

export type FineLine = {
  id: string;
  name: string;
  stock: number;
  unit: string;
  supplier: string;
  status: string;
  soon: boolean;
};

// 입고 (3주 계획 6절 2번). 입고 예정 = 발주를 저장하면 생기는 줄, 최근 발주 = 추천값과 최종값
export type IncomingLine = {
  id: string;
  name: string;
  unit: string;
  qty: number;
  supplier: string;
  pack: number;
  packLabel: string;
  // 도착일 글자. today 면 빨강
  arriveLabel: string;
  today: boolean;
  unitPrice: number;
};

export type OrderStatus = "입고 완료" | "입고 예정" | "보냄" | "전송 대기" | "거절";

export type OrderHistoryRow = {
  id: string;
  date: string;
  name: string;
  recommended: number;
  final: number;
  unit: string;
  supplier: string;
  status: OrderStatus;
  note: string;
};

// 재료별 추정 재고와 단가 이력(최근 8주, 마지막 칸이 이번 주)
export type IngredientState = { stock: number; priceHistory: number[] };

export type ReceivingFeed = {
  incoming: IncomingLine[];
  history: OrderHistoryRow[];
  ingredients: Record<string, IngredientState>;
  // 가게 설정 '첫 입고 확인' (DECISIONS #50)
  firstReceiptDone: boolean;
};

export type Receipt = { qty: number; unitPrice: number };

// 영수증 올리기 (설계 4.4절 · DECISIONS #27 #41). 장마다 대기 → 인식 중 → 완료 | 실패.
// 완료 장은 입고(RECEIVE) 또는 지난 기록(PAST: 재고 확인 날짜 이전이라 재고에 더하지 않는다).
export type ReceiptStatus = "wait" | "read" | "done" | "fail";
// GET /receipts (DECISIONS #66). 바꾸는 주소도 모두 바뀐 뒤의 이 모양을 돌려준다.
export type ReceiptFile = {
  id: string;
  file: string | null;
  at: string;
  st: ReceiptStatus;
  kind: "RECEIVE" | "PAST" | null;
  docDate: string | null;
  lineCount: number;
  error: string | null;
};

// 확인 목록 한 묶음 = 영수증 상품명 하나. guess 가 "" 이면 주기형 품목이 기본.
// qty 는 포장 개수. unitNeeded 이면 '1개 = N 기준 단위'(basePerPack)를 같이 보낸다.
export type ReceiptMatch = Reviewable & {
  name: string;
  receipts: number;
  guess: string;
  unitNeeded: boolean;
  basePerPack: number | null;
  baseUnit: "G" | "ML" | "EA" | null;
  qty: number;
  past: boolean;
};

// GET /receipts/{docId}: 영수증 한 장과 품목 줄. supplierName = 등록 거래처 이름 · 인식한 상호 · 없으면 null
export type ReceiptDetail = ReceiptFile & {
  supplierName: string | null;
  lines: {
    id: string;
    rawName: string;
    qty: number | string | null;
    unit: string | null;
    amount: number | null;
    status: "PENDING" | "CONFIRMED" | "IGNORED";
  }[];
};

export type ReceiptBoard = {
  jobId: string | null;
  files: ReceiptFile[];
  matches: ReceiptMatch[];
  options: { id: string; name: string }[];
  readyDocs: { docId: string; kind: "RECEIVE" | "PAST"; date: string; lines: number }[];
};

// 거래처 및 재료 (3주 계획 6절 4번 · DECISIONS #26 · #28 · #33 · #52)
export type SupplierChannel = "KAKAO" | "PHONE" | "EMAIL" | "WEB" | "MALL";
export type DeliveryDay = "MON" | "TUE" | "WED" | "THU" | "FRI" | "SAT" | "SUN";

// GET /suppliers
export type SupplierApi = {
  id: string;
  name: string;
  channel: SupplierChannel | null;
  leadTimeDays: number | null;
  orderCycleDays: number | null;
  cutoffTime: string | null;
  deliveryDays: DeliveryDay[] | null;
  minOrderAmount: number | null;
  messageTemplate: string | null;
};

// POST · PATCH /suppliers. 보내지 않은 칸은 그대로 둔다
export type SupplierBody = Partial<Omit<SupplierApi, "id">>;

export type Supplier = {
  id: string;
  name: string;
  channel: SupplierChannel | null;
  // "HH:mm", 없으면 ""
  cutoff: string;
  leadDays: number | null;
  cycleDays: number | null;
  days: DeliveryDay[];
  minOrder: number;
  template: string;
};

// 폼에서 고치는 칸
export type SupplierEdit = Partial<Omit<Supplier, "id">>;

export type StorageType = "FROZEN" | "AMBIENT" | "CHILLED" | "FRESH";
export type IngredientKind = "BOM" | "ONE_TO_ONE" | "CYCLE";
// ESTIMATED 추정 · CONFIRMED 답함 · LOCKED 입고 기록이 생겨 바꿀 수 없음 (설계 3.1)
export type UnitStatus = "ESTIMATED" | "CONFIRMED" | "LOCKED";

// 재료 카테고리 (S15P31A308-273, DECISIONS #77). code = 기본 11종(정본 backend DefaultIngredientCategory), 사장님이 만든 것은 null
export type CategoryCode =
  | "MEAT"
  | "SEAFOOD"
  | "VEGETABLE"
  | "FRUIT"
  | "EGG_DAIRY"
  | "GRAIN_NOODLE"
  | "TOFU_BEAN"
  | "SAUCE"
  | "PROCESSED"
  | "BEVERAGE"
  | "OTHER";
export type IngredientCategory = { id: string; name: string; code: CategoryCode | null };

// GET /ingredients (수량은 DB 기준 단위 g · ml · 개)
export type IngredientFullApi = {
  id: string;
  name: string;
  unit: ApiUnit;
  kind: IngredientKind;
  unitStatus: UnitStatus;
  storageType: StorageType;
  safetyDays: number | string;
  maxOrderQty: number | string | null;
  needsReview: boolean;
  reviewReasons: ReviewReason[];
  // 재료 탭 그림 위치 (S15P31A308-268). 백엔드 반영 전에는 오지 않는다
  storageLocation?: Place | null;
  // 없거나 null 이면 기타로 본다
  category?: IngredientCategory | null;
};

// PATCH /ingredients/{id}
export type IngredientBody = {
  unit?: ApiUnit;
  kind?: IngredientKind;
  storageType?: StorageType;
  storageLocation?: Place;
  safetyDays?: number;
  maxOrderQty?: number;
};

// GET /supplier-products
export type SupplierProductApi = {
  id: string;
  supplierId: string;
  ingredientId: string;
  productName: string;
  baseQtyPerPack: number | string | null;
  // 포장 이름(박스 · 팩). 메시지 품목 줄에 쓴다
  packUnit?: string | null;
  price: number | null;
  primary: boolean;
};

// POST · PATCH /supplier-products
export type SupplierProductBody = {
  supplierId?: string;
  ingredientId?: string;
  productName?: string;
  price?: number;
  baseQtyPerPack?: number;
  primary?: boolean;
};

// GET /consumption/daily
export type DailyConsumptionApi = { date: string; ingredientId: string; qty: number | string };

export type IngredientRow = Reviewable & {
  name: string;
  // DB 기준 단위. 화면에는 kg · L 로 바꿔 보인다
  baseUnit: RecipeUnit;
  unitStatus: UnitStatus;
  storage: StorageType;
  kind: IngredientKind;
  // 재료 탭 그림 위치. placeSaved = 서버가 준 값 (아니면 storage · kind 로 정한 기본값)
  place: Place;
  placeSaved: boolean;
  category: IngredientCategory;
  // 추정 재고 (GET /inventory/estimate, 화면 단위). 모르면 null
  stock: number | null;
  // 마지막 실사 시각 (ISO). 없으면 null
  countedAt: string | null;
  // 최근 7일 일 소비량 (화면 단위)
  daily: number[];
  safetyDays: number;
  // 화면 단위. 정하지 않았으면 null
  maxQty: number | null;
  // 주 거래처 상품. 없으면 productId null · supplierId ""
  productId: string | null;
  supplierId: string;
  packLabel: string;
  // 포장 1개 양 (화면 단위). 모르면 0
  packQty: number;
  // 포장 이름. 모르면 ""
  packUnit: string;
  // 화면 단위당 원
  unitPrice: number;
  supplierPriceKnown?: boolean;
};

// 시트에서 고치는 칸 (PATCH /ingredients/{id} · /supplier-products)
export type IngredientEdit = {
  safetyDays: number;
  maxQty: number | null;
  supplierId: string;
  packLabel: string;
  unitPrice: number;
  storage: StorageType;
  kind: IngredientKind;
  baseUnit: RecipeUnit;
};

export type SuppliersScreen = { suppliers: Supplier[]; ingredients: IngredientRow[] };

// GET /recommendations · POST /recommendations/run (RecommendationResponse). 수량은 재료 기준 단위(#42)
export type RecommendationStatus = "PENDING" | "APPROVED" | "REJECTED";
export type RecommendationApi = {
  id: string;
  ingredientId: string;
  ingredientName: string;
  unit: ApiUnit;
  runDate: string;
  estStock: number | string;
  onOrder: number | string;
  leadTimeDemand: number | string;
  safetyStock: number | string;
  reorderPoint: number | string;
  recommendedQty: number | string;
  priceRatio: number | string | null;
  priceLevel: string | null;
  reason: string;
  status: RecommendationStatus;
  finalQty: number | string | null;
  runoutDate: string | null;
  supplierProductId: string | null;
  needsReview: boolean;
  reviewReasons: string[];
};
// PATCH /recommendations/{id}. 한 번만 바뀐다 (PENDING → APPROVED | REJECTED)
export type RecommendationDecision =
  | { action: "approve" }
  | { action: "modify"; finalQty: number }
  | { action: "reject"; reason?: string };

// POST /purchase-orders/from-recommendations 응답 (거래처마다 한 장)
export type PurchaseOrderApi = {
  id: string;
  supplierId: string;
  supplierName: string;
  status: "DRAFT" | "SENT" | "CANCELLED";
  messageText: string;
  items: { id: string; recommendationId: string; ingredientId: string; qty: number | string }[];
};

export type RecommendationRunApi = {
  runDate: string;
  createdCount: number;
  existingCount: number;
  noOrderCount: number;
  recommendations: RecommendationApi[];
};

// GET /receiving (ReceivingFeedResponse)
export type IncomingApi = {
  orderId: string;
  itemId: string;
  ingredientId: string;
  name: string;
  supplier: string;
  packLabel: string | null;
  // 화면 표기 단위 g · ml · 개 (#56)
  unit: RecipeUnit;
  basePerPack: number | string | null;
  qty: number | string | null;
  unitPrice: number | string | null;
  expectedDate: string;
  today: boolean;
};
export type HistoryApi = {
  orderId: string;
  itemId: string;
  date: string;
  name: string;
  unit: RecipeUnit;
  recommendedQty: number | string | null;
  finalQty: number | string | null;
  supplier: string;
  status: "DRAFT" | "SENT" | "RECEIVED" | "CANCELLED";
  receivedQty: number | string | null;
  receivedAt: string | null;
};
// GET /purchase-orders/history?month=YYYY-MM (API.md). qty 는 기준 단위, amount 는 원 정수(모르면 null)
export type MonthHistoryApi = {
  month: string;
  lines: {
    date: string;
    itemId: string;
    ingredientName: string;
    supplierName: string;
    qty: number | string | null;
    unit: RecipeUnit;
    amount: number | null;
    status: "SENT" | "RECEIVED" | "CANCELLED";
  }[];
  totalAmount: number;
  earliestMonth: string | null;
};
export type ReceivingFeedApi = {
  incoming: IncomingApi[];
  history: HistoryApi[];
  firstReceiptDone: boolean;
};

// GET /inventory/estimate
export type InventoryEstimateApi = {
  ingredientId: string;
  ingredientName: string;
  unit: ApiUnit;
  estimatedQty: number | string | null;
  needsCount: boolean;
  runOutDate: string | null;
  lastCountedAt: string | null;
};

// GET /ingredients/{id}/price-history. weekly 는 단위 단가(원 / 기준 단위 1, #57), 마지막 칸이 이번 주
export type PriceHistoryApi = {
  ingredientId: string;
  unit: ApiUnit;
  weekly: (number | string | null)[];
};

// GET /ingredients/{id}/offers (API.md 7.1). 단가 × toIngredientUnitFactor = 원 / 재료 기준 단위 1, 계수 null 이면 비교 못 함
export type IngredientOffersApi = {
  toIngredientUnitFactor: number | string | null;
  sites: {
    site: string;
    bestUnitPrice: number | string;
    collectedOn: string;
    delivery?: string;
    synthetic?: boolean;
    offers: {
      url: string;
      price?: number;
      unitPrice?: number | string;
      productName?: string;
      spec?: string | null;
    }[];
  }[];
};

// GET /ingredients/{id}/market-price-history (API.md 7.2). 수집이 없는 날은 점이 없다
export type MarketPriceHistoryApi = {
  toIngredientUnitFactor: number | string | null;
  from?: string;
  to?: string;
  sites: {
    site: string;
    delivery?: string;
    synthetic?: boolean;
    points: { date: string; unitPrice: number | string }[];
  }[];
};

// 발주 탭 (DECISIONS #61): 오늘 주문 줄 + 충분한 재료 + 달력
export type OrderFeed = {
  storeId?: string;
  groups: SupplierOrderGroup[];
  fine: FineLine[];
  // "YYYY-MM-DD" → 그날 주문 · 지난 발주
  calendar: Record<string, CalendarEvent[]>;
  today: string;
};
export type CalendarEvent = {
  name: string;
  supplierId: string;
  // today = 오늘 주문, plan = 앞으로 주문할 날, done = 지난 발주
  kind: "today" | "plan" | "done" | "rejected";
  lineId?: string;
  qty?: string;
  status?: string;
};

// 이번 주 매출 예측 (홈 · 발주 탭 매출 카드와 모달, GET /sales-forecast/this-week, S15P31A308-267).
// pct = 평소 주보다 몇 %, days = 응답에 포함된 날짜별 예상 매출 지수(부분 날짜 허용), factors = 이유별 기여(최대 3개), analysis = 설명 문장.
// 예측이 없으면(서울 밖 가게 · 배치 전) pct 가 null 이고 days 가 빈다. LLM 이 실패하면 analysis 만 null
export type SalesForecast = {
  pct: number | null;
  reason: string | null;
  range: string;
  days: { label: string; value: number; today?: boolean }[];
  factors: { name: string; note: string; pct: number }[];
  analysis: string | null;
};
