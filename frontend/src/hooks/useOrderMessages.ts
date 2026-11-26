"use client";

import { useState } from "react";
import { useToast } from "@/components/Toast";
import { createPurchaseOrders, decideRecommendation, type OrderSourceApi } from "@/lib/api";
import type { Orders } from "@/lib/orderHome";
import {
  buildMessages,
  bySource,
  closePlan,
  isMarket,
  lineIds,
  setSent,
  sourceOptions,
  resolvedSource,
  type MessageFlags,
  type SupplierMessage,
} from "@/lib/orderMessage";
import type { RecommendationDecision, SupplierOrderGroup } from "@/lib/types";

const noFlags: MessageFlags = { filled: {}, sent: {} };
// 공유 시트는 폰에서만 (시안 CAN_SHARE). 데스크톱 · 미지원 브라우저는 복사 (DECISIONS #49 · #79)
const canShareNow = () =>
  typeof navigator.share === "function" && window.matchMedia("(pointer: coarse)").matches;
const r3 = (n: number) => Math.round(n * 1000) / 1000;

// 카카오톡 공유 (DECISIONS #79). 키가 있을 때만 공식 CDN 에서 한 번 불러온다. 실패하면 null
type KakaoSdk = {
  init: (key: string) => void;
  isInitialized: () => boolean;
  Share: { sendDefault: (o: object) => void };
};
const KAKAO_KEY = process.env.NEXT_PUBLIC_KAKAO_JS_KEY;
let kakao: Promise<KakaoSdk | null> | undefined;
const loadKakao = () =>
  (kakao ??= new Promise((done) => {
    if (!KAKAO_KEY) return done(null);
    const s = document.createElement("script");
    s.src = "https://t1.kakaocdn.net/kakao_js_sdk/2.8.4/kakao.min.js";
    s.integrity = "sha384-BGX7Wb16GmRFagri4bLJV2DgipVZaU2AI812GZtHtHzzDQJnfHasDXN5houxv45w";
    s.crossOrigin = "anonymous";
    s.onload = () => {
      try {
        const k = (window as unknown as { Kakao: KakaoSdk }).Kakao;
        if (!k.isInitialized()) k.init(KAKAO_KEY);
        done(k.isInitialized() ? k : null);
      } catch {
        done(null);
      }
    };
    s.onerror = () => done(null);
    document.head.append(s);
  }));

// 텍스트 템플릿은 200자까지. 웹은 완료 콜백이 없어 창을 열면 보낸 것으로 본다
async function shareKakao(text: string) {
  if (text.length > 200) return false;
  const k = await loadKakao();
  if (!k) return false;
  const url = location.origin;
  try {
    k.Share.sendDefault({ objectType: "text", text, link: { mobileWebUrl: url, webUrl: url } });
    return true;
  } catch {
    return false;
  }
}

// 주문 메시지 시트. 앱은 직접 보내지 않는다 (DECISIONS #1 · #49).
// 보냄 · 취소는 표시만 하고, 시트를 닫을 때 보냄인 거래처만 발주로 저장한다 (DECISIONS #63 · #79):
// 추천 승인(수량을 바꿨으면 수정) → 발주서. 저장한 줄은 오늘 주문에서 빠지고(onPlaced), 안 보낸 줄은 담은 채로 남는다
export function useOrderMessages(
  groups: SupplierOrderGroup[],
  orders: Orders,
  store: string,
  onPlaced: (lineIds: string[]) => void,
  compare = true,
) {
  const [open, setOpen] = useState(false);
  // pick = 주문처 선택 (DECISIONS #64), msg = 주문 메시지
  const [step, setStep] = useState<"pick" | "msg">("pick");
  const [src, setSrc] = useState<Record<string, string>>({});
  const [flags, setFlags] = useState(noFlags);
  const [busy, setBusy] = useState(false);
  // 저장이 끝난 거래처. 일부가 실패해 다시 닫을 때 건너뛴다
  const [saved, setSaved] = useState<string[]>([]);
  const { toast, show } = useToast();
  const selected = Object.fromEntries(
    groups.flatMap((g) => g.lines.map((l) => [l.id, resolvedSource(l, g, compare, src[l.id]).key])),
  );
  const messages = buildMessages(bySource(groups, selected), orders, flags, store);
  // 주문처 선택 줄: 발주한 줄마다 기존 거래처 · 식봄 · 쿠팡
  const pickRows = groups.flatMap((g) =>
    g.lines
      .filter((l) => l.id in orders.approved)
      .map((l) => ({
        line: l,
        qty: orders.approved[l.id],
        options: sourceOptions(l, g, compare),
        chosen: selected[l.id] ?? g.id,
      })),
  );

  // 추천 승인 · 수정 → 발주서. 이미 결정한 추천(다시 시도)은 건너뛴다
  const save = async (m: SupplierMessage) => {
    const ids = lineIds(m);
    for (const id of ids) {
      const line = m.group.lines.find((l) => l.id === id)!;
      const q = orders.approved[id];
      const body: RecommendationDecision =
        r3(q) === r3(line.qty)
          ? { action: "approve" }
          : { action: "modify", finalQty: r3(q * (line.baseFactor ?? 1)) };
      const r = await decideRecommendation(id, body);
      if (!r.success && r.error?.code !== "RECOMMENDATION_ALREADY_DECIDED") return false;
    }
    // 쇼핑몰 묶음은 묶음 id(sikbom · coupang)가 곧 주문처. 거래처 묶음은 예전 요청 그대로
    const source = isMarket(m.group.id) ? (m.group.id.toUpperCase() as OrderSourceApi) : undefined;
    const po = await createPurchaseOrders(ids, source);
    if (po.error?.code === "PURCHASE_ORDER_SOURCE_PRICE_UNSET") return "unset";
    return po.success || po.error?.code === "PURCHASE_ORDER_ALREADY_ORDERED";
  };

  const copy = async (m: SupplierMessage) => {
    try {
      await navigator.clipboard.writeText(m.text);
      show("복사됨");
    } catch {
      // 클립보드가 막힌 브라우저: 본문을 선택해 둔다
      const el = document.getElementById(`msg-${m.group.id}`);
      const sel = window.getSelection();
      if (el && sel) {
        const r = document.createRange();
        r.selectNodeContents(el);
        sel.removeAllRanges();
        sel.addRange(r);
      }
      show("길게 눌러 복사");
    }
  };

  // 보냄 (DECISIONS #79): 카카오톡 공유 → 안 되면 폰은 공유 시트, PC 는 복사. 쇼핑몰은 표시만
  const send = async (m: SupplierMessage) => {
    if (!isMarket(m.group.id) && !(await shareKakao(m.text))) {
      if (canShareNow()) {
        try {
          await navigator.share({ text: m.text });
        } catch (e) {
          // 공유 시트를 그냥 닫으면 보내지 않은 것
          if (e instanceof DOMException && e.name === "AbortError") return;
          await copy(m);
        }
      } else await copy(m);
    }
    setFlags((f) => setSent(f, m.group.id, true));
  };

  const close = async () => {
    // 저장 중에는 닫지 않는다. 닫힌 뒤 dialog 의 close 이벤트로 한 번 더 불려도 무시
    if (!open || busy) return;
    const plan = closePlan(messages, saved);
    const ok = [...saved];
    let unset = false;
    if (plan.save.length) {
      setBusy(true);
      for (const m of plan.save) {
        const r = await save(m);
        if (r === true) ok.push(m.group.id);
        else if (r === "unset") unset = true;
      }
      setBusy(false);
    }
    if (ok.length < saved.length + plan.save.length) {
      setSaved(ok);
      return show(unset ? "단가를 먼저 넣어 주세요" : "저장하지 못했어요. 다시 눌러 주세요");
    }
    if (plan.placed.length) onPlaced(plan.placed);
    setFlags(noFlags);
    setSaved([]);
    setSrc({});
    setOpen(false);
  };

  return {
    open,
    // 주문처를 이미 고른 채 열면(발주 탭 담기) 주문처 선택을 건너뛴다
    show: (chosen?: Record<string, string>) => {
      if (chosen) setSrc(chosen);
      setStep(chosen ? "msg" : "pick");
      setOpen(true);
      void loadKakao();
    },
    step,
    pickRows,
    next: () => setStep("msg"),
    src,
    pick: (lineId: string, key: string) => setSrc((s) => ({ ...s, [lineId]: key })),
    close,
    done: close,
    messages,
    busy,
    saved,
    toast,
    send,
    cancel: (id: string) => setFlags((f) => setSent(f, id, false)),
    fill: (id: string) => setFlags((f) => ({ ...f, filled: { ...f.filled, [id]: true } })),
  };
}
