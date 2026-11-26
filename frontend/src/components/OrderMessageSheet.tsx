"use client";

import type { ReactNode } from "react";
import type { useOrderMessages } from "@/hooks/useOrderMessages";
import { fmt, won } from "@/lib/format";
import { isMarket, topUpLabel, type SupplierMessage } from "@/lib/orderMessage";
import { BottomSheet } from "./BottomSheet";
import { Button } from "./Button";
import { Tag } from "./Chip";
import styles from "./OrderMessageSheet.module.css";

type Sheet = ReturnType<typeof useOrderMessages>;

// 시안 symbol i-check · i-alert · i-x, 종이비행기 = 보냄 (DECISIONS #79)
const Ic = ({ children }: { children: ReactNode }) => (
  <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
    {children}
  </svg>
);
const CHECK = <path d="M5 12l5 5L20 7" />;
const ALERT = (
  <>
    <path d="M12 4l9 16H3z" />
    <path d="M12 10v4M12 17h0" />
  </>
);
const X = <path d="M6 6l12 12M18 6L6 18" />;
const PLANE = (
  <>
    <path d="M21 3L11 13" />
    <path d="M21 3l-6.5 18-3.5-8-8-3.5z" />
  </>
);

// 합계 줄: 최소 주문금액 충족 초록 · 미달 빨강 + 부족 금액 + 보충 버튼
function Sum({ m, sheet }: { m: SupplierMessage; sheet: Sheet }) {
  const g = m.group;
  if (!g.minOrder) return <span>{won(m.total)}</span>;
  if (!m.short)
    return (
      <>
        <span className={styles.isOk}>
          <Ic>{CHECK}</Ic> {won(m.total)}
        </span>
        <span>최소 {won(g.minOrder)}</span>
      </>
    );
  return (
    <>
      <span className={styles.isRed}>
        <Ic>{ALERT}</Ic> {won(m.total)}
      </span>
      <span>
        최소 {won(g.minOrder)}, {won(m.short)} 부족
      </span>
      {!m.filled && g.topUp.length > 0 && (
        <Button variant="ghost" small onClick={() => sheet.fill(g.id)}>
          {topUpLabel(g.topUp)}
        </Button>
      )}
    </>
  );
}

// 보냄 → ✓ 보냄 + 왼쪽 취소. 저장은 시트를 닫을 때라 저장이 끝난 거래처만 취소가 없다 (DECISIONS #79)
function Acts({ m, sheet }: { m: SupplierMessage; sheet: Sheet }) {
  const id = m.group.id;
  if (m.sent)
    return (
      <span className={styles.acts}>
        {!sheet.saved.includes(id) && (
          <Button variant="ghost" small disabled={sheet.busy} onClick={() => sheet.cancel(id)}>
            <Ic>{X}</Ic>
            취소
          </Button>
        )}
        <span className={styles.sent}>
          <Ic>{CHECK}</Ic>
          보냄
        </span>
      </span>
    );
  // 쇼핑몰은 메시지 대신 주문 링크. 주문을 마치고 누른다 (DECISIONS #63)
  if (isMarket(id))
    return (
      <Button variant="ghost" small disabled={sheet.busy} onClick={() => sheet.send(m)}>
        주문 완료
      </Button>
    );
  return (
    <Button small disabled={sheet.busy} onClick={() => sheet.send(m)}>
      <Ic>{PLANE}</Ic>
      보냄
    </Button>
  );
}

// 쇼핑몰 묶음: 품목마다 그 몰 상품 페이지로 (시안 order_v5.html 웹 주문 링크)
function Links({ m }: { m: SupplierMessage }) {
  const key = m.group.id;
  return (
    <div className={styles.links}>
      {m.group.lines.map((l) => {
        const candidate = l.price?.markets?.find((x) => x.key === key);
        const url = candidate?.synthetic
          ? undefined
          : (candidate?.url ??
            (key === "sikbom"
              ? l.price?.sikbom.url
              : key === "coupang"
                ? l.price?.coupang.url
                : undefined));
        return url ? (
          <a key={l.id} href={url} target="_blank" rel="noopener noreferrer">
            {l.name} 주문하러 가기
          </a>
        ) : (
          <span key={l.id}>{l.name} 링크 없음</span>
        );
      })}
    </div>
  );
}

// 주문처 선택 (시안 app_all.html sheetKind pick, DECISIONS #64): 품목마다 기존 거래처 · 식봄 · 쿠팡
function SourcePick({ sheet }: { sheet: Sheet }) {
  return (
    <div>
      {sheet.pickRows.map((r) => (
        <div key={r.line.id} className={styles.msg}>
          <div className={styles.head}>
            <h3>{r.line.name}</h3>
            <span className={`${styles.q} num`}>
              {fmt(r.qty)}
              <u>{r.line.unit}</u>
            </span>
          </div>
          <div className={styles.srcOpts} role="radiogroup" aria-label={`${r.line.name} 주문처`}>
            {r.options.map((o) => (
              <button
                type="button"
                key={o.key}
                role="radio"
                aria-checked={r.chosen === o.key}
                disabled={o.price === null || o.stale || o.available === false}
                className={styles.src}
                onClick={() => sheet.pick(r.line.id, o.key)}
              >
                <span className={styles.srcName}>{o.name}</span>
                <b className="num">{o.price === null ? "없음" : won(o.price * r.qty)}</b>
                {o.price !== null && (
                  <small>
                    {o.lowest && <em>최저</em>}
                    {o.stale
                      ? `${o.on ?? "어제"} 가격`
                      : `${o.price.toLocaleString("ko-KR")}원/${r.line.unit}`}
                  </small>
                )}
              </button>
            ))}
          </div>
        </div>
      ))}
      <Button block onClick={sheet.next}>
        다음
      </Button>
    </div>
  );
}

// 주문 메시지 시트 (시안 #msgDialog). 거래처 블록마다 보냄, 닫을 때 보낸 거래처만 저장 (DECISIONS #79)
export function OrderMessageSheet({ sheet }: { sheet: Sheet }) {
  if (sheet.step === "pick")
    return (
      <BottomSheet open={sheet.open} title="어디서 주문할까요" onClose={sheet.close}>
        <SourcePick sheet={sheet} />
      </BottomSheet>
    );
  return (
    <BottomSheet open={sheet.open} title="주문 메시지" onClose={sheet.close}>
      <div>
        {sheet.messages.map((m) => (
          <div key={m.group.id} className={m.sent ? `${styles.msg} ${styles.isSent}` : styles.msg}>
            <div className={styles.head}>
              <h3>
                {m.group.name} <Tag>{m.group.channel}</Tag>
              </h3>
              <Acts m={m} sheet={sheet} />
            </div>
            {isMarket(m.group.id) ? (
              <Links m={m} />
            ) : (
              <div className={styles.body} id={`msg-${m.group.id}`}>
                {m.text}
              </div>
            )}
            <div className={styles.sum}>
              <Sum m={m} sheet={sheet} />
              {m.group.cutoff && <span>마감 {m.group.cutoff}</span>}
            </div>
          </div>
        ))}
      </div>
      <Button variant="ghost" block disabled={sheet.busy} onClick={sheet.done}>
        완료
      </Button>
      {sheet.toast}
    </BottomSheet>
  );
}
