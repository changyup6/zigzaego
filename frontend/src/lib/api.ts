import type {
  ApiResponse,
  ClosingTime,
  Credentials,
  DailyConsumptionApi,
  IngredientApi,
  IngredientBody,
  IngredientFullApi,
  Job,
  MenuApi,
  MonthHistoryApi,
  OnboardingJobRequest,
  IngredientOffersApi,
  InventoryEstimateApi,
  MarketPriceHistoryApi,
  PlaceCandidate,
  PriceHistoryApi,
  PurchaseOrderApi,
  ReceiptBoard,
  ReceiptDetail,
  RecommendationDecision,
  ReceivingFeedApi,
  RecommendationRunApi,
  RecipeApi,
  RecipeUnit,
  RecipeAnswer,
  RecipeBook,
  RecipeQuestion,
  SalesForecast,
  StoreMe,
  SupplierApi,
  SupplierBody,
  SupplierProductApi,
  SupplierProductBody,
  Tokens,
} from "@/lib/types";
import type { ClosingBody, ClosingItem, ClosingParse } from "@/lib/closingCheck";

const BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const RT_KEY = "wiggly-refresh";
const JOB_REQ_KEY = "wiggly-onboarding-job";
const SESSION_KEY = "wiggly-auth-session";
let memorySession = "";

// 토큰 값 대신 로그인 세션 식별자로 지연된 저장을 격리한다. 토큰 refresh 때는 유지한다.
export function authSession() {
  try {
    const saved = sessionStorage.getItem(SESSION_KEY);
    if (saved) return saved;
    memorySession ||= crypto.randomUUID();
    sessionStorage.setItem(SESSION_KEY, memorySession);
  } catch {
    memorySession ||= crypto.randomUUID();
  }
  return memorySession;
}

// access token 은 메모리, refresh token 은 sessionStorage (전달 방식 미정 · ADR-0005).
// ponytail: 로그인 API MR에서 HttpOnly 쿠키로 정해지면 이 두 함수만 바꾼다.
let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;

export function setTokens(t: Tokens | null, keepSession = false) {
  if (!keepSession) {
    memorySession = crypto.randomUUID();
    try {
      sessionStorage.setItem(SESSION_KEY, memorySession);
    } catch {}
  }
  accessToken = t?.accessToken ?? null;
  try {
    if (t) sessionStorage.setItem(RT_KEY, t.refreshToken);
    else sessionStorage.removeItem(RT_KEY);
  } catch {}
}

const fail = <T>(code: string, message: string): ApiResponse<T> => ({
  success: false,
  data: null,
  error: { code, message },
});

async function send<T>(
  path: string,
  init?: RequestInit,
): Promise<{ status: number; body: ApiResponse<T> }> {
  try {
    const res = await fetch(BASE + path, {
      ...init,
      headers: {
        ...init?.headers,
        ...(init?.body && !(init.body instanceof FormData)
          ? { "Content-Type": "application/json" }
          : {}),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
    });
    const body = (await res.json().catch(() => null)) as ApiResponse<T> | null;
    return { status: res.status, body: body ?? fail("COMMON_BAD_RESPONSE", res.statusText) };
  } catch {
    return { status: 0, body: fail("COMMON_NETWORK", "network error") };
  }
}

function readRefreshToken(): string | null {
  try {
    return sessionStorage.getItem(RT_KEY);
  } catch {
    return null;
  }
}

function toLogin() {
  setTokens(null);
  if (typeof window !== "undefined" && window.location.pathname !== "/login")
    window.location.replace("/login");
}

// 동시 401 은 refresh 한 번을 같이 기다린다.
function refresh(): Promise<boolean> {
  refreshing ??= (async () => {
    const rt = readRefreshToken();
    const session = authSession();
    if (!rt) return false;
    const { body } = await send<Tokens>("/auth/refresh", {
      method: "POST",
      body: JSON.stringify({ refreshToken: rt }),
    });
    if (authSession() !== session) return false;
    setTokens(body.success ? body.data : null, true);
    return body.success;
  })().finally(() => (refreshing = null));
  return refreshing;
}

export async function api<T>(
  path: string,
  init?: RequestInit,
  session = authSession(),
): Promise<ApiResponse<T>> {
  const changed = () => authSession() !== session;
  const sessionError = () => fail<T>("AUTH_SESSION_CHANGED", "로그인한 가게가 바뀌었어요");
  if (changed()) return sessionError();
  // 새로고침으로 메모리의 access token 이 사라졌으면 refresh token 으로 먼저 되살린다.
  if (
    !accessToken &&
    path !== "/auth/login" &&
    path !== "/auth/signup" &&
    path !== "/auth/refresh" &&
    readRefreshToken()
  )
    await refresh();
  if (changed()) return sessionError();
  let r = await send<T>(path, init);
  if (changed()) return sessionError();
  if (r.status !== 401 || path === "/auth/refresh") return r.body;
  const code = r.body.error?.code;
  if (code === "AUTH_TOKEN_EXPIRED") {
    const refreshed = await refresh();
    if (changed()) return sessionError();
    if (refreshed) r = await send<T>(path, init);
    else toLogin();
  } else if (code === "AUTH_INVALID_TOKEN" || code === "COMMON_UNAUTHORIZED") toLogin();
  return changed() ? sessionError() : r.body;
}

export async function login(c: Credentials): Promise<ApiResponse<Tokens>> {
  const r = await api<Tokens>("/auth/login", { method: "POST", body: JSON.stringify(c) });
  if (r.success) setTokens(r.data);
  return r;
}

// 가입 화면은 약관 셋을 모두 켜야 제출된다 (DECISIONS #67).
export async function signup(c: Credentials): Promise<ApiResponse<Tokens>> {
  const r = await api<Tokens>("/auth/signup", {
    method: "POST",
    body: JSON.stringify({ ...c, agreements: ["TERMS", "PRIVACY", "COLLECTION"] }),
  });
  if (r.success) setTokens(r.data);
  return r;
}

export async function logout() {
  const rt = readRefreshToken();
  if (rt)
    await send("/auth/logout", { method: "POST", body: JSON.stringify({ refreshToken: rt }) });
  setTokens(null);
}

export const getStoreMe = () => api<StoreMe>("/stores/me");
export const setPriceCompare = (priceCompare: boolean) =>
  api<StoreMe>("/stores/me", { method: "PATCH", body: JSON.stringify({ priceCompare }) });
// 요일별 마감 시각 전체를 바꾼다 (#88, S15P31A308-248).
export const putClosingTimes = (closingTimes: ClosingTime[]) =>
  api<StoreMe>("/stores/me/closing-times", {
    method: "PUT",
    body: JSON.stringify({ closingTimes }),
  });

export const getJob = (id: string) => api<Job>(`/jobs/${encodeURIComponent(id)}`);

export const searchPlaces = (query: string) =>
  api<PlaceCandidate[]>(`/onboarding/places?query=${encodeURIComponent(query)}`);

// 다시 시도는 같은 요청을 다시 보내 새 job 을 만든다 (백엔드 계약). 요청은 이 탭에만 기억한다.
export async function startOnboardingJob(req: OnboardingJobRequest) {
  const r = await api<Job>("/onboarding/jobs", { method: "POST", body: JSON.stringify(req) });
  if (r.success)
    try {
      sessionStorage.setItem(JOB_REQ_KEY, JSON.stringify(req));
    } catch {}
  return r;
}

export function retryOnboardingJob() {
  try {
    const raw = sessionStorage.getItem(JOB_REQ_KEY);
    if (raw) return startOnboardingJob(JSON.parse(raw) as OnboardingJobRequest);
  } catch {}
  return Promise.resolve(fail<Job>("ONBOARDING_RETRY_UNAVAILABLE", "다시 시도할 수 없음"));
}

export function uploadMenuPhoto(photo: File) {
  const body = new FormData();
  body.append("file", photo);
  return api<Job>("/onboarding/jobs/menu-photo", { method: "POST", body });
}

export const getRecipeQuestions = () => api<RecipeQuestion[]>("/onboarding/recipe-questions");

// 메뉴에는 판매 수가 없다. 이 화면은 재료만 읽는다.
export const getSavedRecipes = () => api<Pick<RecipeBook, "ingredients">>("/onboarding/recipes");

export const confirmRecipes = (answers: RecipeAnswer[]) =>
  api<RecipeBook>("/onboarding/recipes/confirm", { method: "POST", body: JSON.stringify(answers) });

export const skipRecipes = () => api<RecipeBook>("/onboarding/recipes/skip", { method: "POST" });

// 레시피 탭
export const getMenus = () => api<MenuApi[]>("/menus");
export const getRecipeLines = () => api<RecipeApi[]>("/recipes");
export const getIngredients = () => api<IngredientApi[]>("/ingredients");

const API_UNIT = { g: "G", ml: "ML", 개: "EA" } as const;

// 새 재료 폼에는 종류 · 보관 방식 칸이 없다. BOM(메뉴 판매 × 1인분) · 냉장으로 만든다.
export const createIngredient = (name: string, unit: RecipeUnit) =>
  api<IngredientApi>("/ingredients", {
    method: "POST",
    body: JSON.stringify({ name, unit: API_UNIT[unit], kind: "BOM", storageType: "CHILLED" }),
  });

export const createRecipeLine = (menuId: string, ingredientId: string, qtyPerServing: number) =>
  api<RecipeApi>("/recipes", {
    method: "POST",
    body: JSON.stringify({ menuId, ingredientId, qtyPerServing }),
  });

export const updateRecipeLine = (id: string, qtyPerServing: number) =>
  api<RecipeApi>(`/recipes/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify({ qtyPerServing }),
  });

export const deleteRecipeLine = (id: string) =>
  api<null>(`/recipes/${encodeURIComponent(id)}`, { method: "DELETE" });

// 거래처 및 재료 탭
const json = (body: unknown) => JSON.stringify(body);
const enc = encodeURIComponent;

export const getSuppliers = () => api<SupplierApi[]>("/suppliers");
export const createSupplier = (body: SupplierBody) =>
  api<SupplierApi>("/suppliers", { method: "POST", body: json(body) });
export const updateSupplier = (id: string, body: SupplierBody) =>
  api<SupplierApi>(`/suppliers/${enc(id)}`, { method: "PATCH", body: json(body) });
export const deleteSupplier = (id: string) =>
  api<null>(`/suppliers/${enc(id)}`, { method: "DELETE" });

export const getSupplierProducts = () => api<SupplierProductApi[]>("/supplier-products");
export const createSupplierProduct = (body: SupplierProductBody) =>
  api<SupplierProductApi>("/supplier-products", { method: "POST", body: json(body) });
export const updateSupplierProduct = (id: string, body: SupplierProductBody) =>
  api<SupplierProductApi>(`/supplier-products/${enc(id)}`, { method: "PATCH", body: json(body) });

export const getIngredientsFull = () => api<IngredientFullApi[]>("/ingredients");
// 재고 실사 (POST /stock-counts). 수량은 기준 단위 g · ml · 개
export const postStockCounts = (counts: { ingredientId: string; qty: number }[]) =>
  api<InventoryEstimateApi[]>("/stock-counts", { method: "POST", body: json({ counts }) });
export const updateIngredient = (id: string, body: IngredientBody) =>
  api<IngredientFullApi>(`/ingredients/${enc(id)}`, { method: "PATCH", body: json(body) });

// 추천: 오늘 실행이 끝났으면 기존 추천만 돌려준다 (#59). 홈 · 발주가 열 때 부른다
export const runRecommendations = () =>
  api<RecommendationRunApi>("/recommendations/run", { method: "POST" });
export const getReceiving = () => api<ReceivingFeedApi>("/receiving");
export const getMonthHistory = (month: string) =>
  api<MonthHistoryApi>(`/purchase-orders/history?month=${month}`);
export const decideRecommendation = (id: string, body: RecommendationDecision) =>
  api<unknown>(`/recommendations/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
// 승인한 추천 → 거래처별 발주서 · 주문 메시지 (보낸 줄만, DECISIONS #63).
// source 를 주면 그 주문처(식봄 · 쿠팡)로 줄마다 items 를 보낸다 (S15P31A308-201)
export type OrderSourceApi = "SUPPLIER" | "SIKBOM" | "COUPANG" | "MARKETBOM" | "ORDERLINK";
export const createPurchaseOrders = (recommendationIds: string[], source?: OrderSourceApi) =>
  api<PurchaseOrderApi[]>("/purchase-orders/from-recommendations", {
    method: "POST",
    body: JSON.stringify(
      source
        ? { items: recommendationIds.map((recommendationId) => ({ recommendationId, source })) }
        : { recommendationIds },
    ),
  });
export const getInventory = () => api<InventoryEstimateApi[]>("/inventory/estimate");
export const getPriceHistory = (ingredientId: string) =>
  api<PriceHistoryApi>(`/ingredients/${encodeURIComponent(ingredientId)}/price-history`);
// 식자재몰 최저가 · 일별 추이 (API.md 7.1 · 7.2)
export const getOffers = (ingredientId: string) =>
  api<IngredientOffersApi>(`/ingredients/${enc(ingredientId)}/offers`);
export const getMarketPriceHistory = (ingredientId: string, days: number) =>
  api<MarketPriceHistoryApi>(`/ingredients/${enc(ingredientId)}/market-price-history?days=${days}`);

// 영수증 (DECISIONS #66). 바꾸는 주소도 모두 바뀐 뒤의 영수증 화면을 돌려준다
export const getReceipts = () => api<ReceiptBoard>("/receipts");
export type DemoPreparationStatus = {
  eligible: boolean;
  status?: "NOT_STARTED" | "PREPARING" | "DATA_READY" | "READY" | "FAILED";
};
// 기존 서버의 계정 판정만 읽는다. 준비 시작/재시도·OCR 설정은 바꾸지 않는다.
export const getReceiptDemoEligibility = () =>
  api<DemoPreparationStatus>("/stores/me/demo-preparation");
export function uploadReceipts(files: File[]) {
  const body = new FormData();
  files.forEach((f) => body.append("files", f));
  return api<ReceiptBoard>("/receipts", { method: "POST", body });
}
export const getReceiptDetail = (id: string) => api<ReceiptDetail>(`/receipts/${enc(id)}`);
export const retryReceipt = (id: string) =>
  api<ReceiptBoard>(`/receipts/${enc(id)}/retry`, { method: "POST" });
export const deleteReceipt = (id: string) =>
  api<ReceiptBoard>(`/receipts/${enc(id)}`, { method: "DELETE" });
// choice: 재료 id · "cycle"(주기형 품목) · "ignore"
export const confirmReceiptMatch = (id: string, choice: string, basePerPack: number | null) =>
  api<ReceiptBoard>(`/receipts/matches/${enc(id)}/confirm`, {
    method: "POST",
    body: json({ choice, basePerPack }),
  });
export const confirmReceiptDocs = (docIds: string[]) =>
  api<ReceiptBoard>("/receipts/docs/confirm", { method: "POST", body: json({ docIds }) });

export const getDailyConsumption = (from: string, to: string) =>
  api<DailyConsumptionApi[]>(`/consumption/daily?from=${from}&to=${to}`);

// 마감 확인 (DECISIONS #88). parse 는 저장하지 않고 해석 결과만 돌려준다
export const getClosingItems = () => api<ClosingItem[]>("/closing-check/items");
export const parseClosingText = (text: string) =>
  api<ClosingParse>("/closing-check/parse", { method: "POST", body: json({ text }) });
export const saveClosingCheck = (body: ClosingBody) =>
  api<unknown>("/closing-check", { method: "POST", body: json(body) });

// 이번 주 매출 예측 (S15P31A308-267)
export const getSalesForecast = () => api<SalesForecast>("/sales-forecast/this-week");
