"use client";

import { useState, useTransition } from "react";
import { lastWeek } from "@/hooks/useSuppliers";
import { fmt } from "@/lib/format";
import { todayYmd } from "@/lib/today";
import { PLACE_LABEL } from "@/lib/stockPlace";
import { packPriceLabel, safetyStock, shownUnit, supplierName } from "@/lib/suppliers";
import type { IngredientRow, Supplier } from "@/lib/types";
import { fmtStock } from "@/lib/units";
import { Button } from "./Button";
import d from "./Detail.module.css";
import { Modal } from "./Modal";

const WD = ["일", "월", "화", "수", "목", "금", "토"];
const kst = (iso: string | Date) =>
  new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(new Date(iso));

// 재료 모달 (시안 stock_v3.html): 추정 재고 + 재고 직접 입력, 정보, 최근 7일 사용량
export function IngredientModal({
  row,
  suppliers,
  onCount,
  onEdit,
  onClose,
}: {
  row: IngredientRow;
  suppliers: Supplier[];
  // 화면 단위 수량으로 실사 (POST /stock-counts)
  onCount: (row: IngredientRow, qty: number) => Promise<boolean>;
  onEdit: () => void;
  onClose: () => void;
}) {
  const [counting, setCounting] = useState(false);
  const [v, setV] = useState(row.stock === null ? "" : String(Math.round(row.stock * 10) / 10));
  const [pending, start] = useTransition();
  const u = shownUnit(row.baseUnit);
  const avg = row.daily.length ? row.daily.reduce((s, x) => s + x, 0) / row.daily.length : 0;
  const safe = safetyStock(row.daily, row.safetyDays);
  const low = row.stock !== null && row.stock < safe;
  const today = row.countedAt !== null && kst(row.countedAt) === todayYmd();
  const days = lastWeek();
  const max = Math.max(...row.daily, 0.0001);
  const qty = Number(v);

  const save = () =>
    start(async () => {
      if (v !== "" && qty >= 0 && (await onCount(row, qty))) setCounting(false);
    });

  return (
    <Modal
      open
      label={row.name}
      onClose={onClose}
      head={
        <>
          <h2>{row.name}</h2>
          <span className={d.tag}>{PLACE_LABEL[row.place]}</span>
        </>
      }
    >
      <div className={d.blk}>
        <div className={d.stock}>
          <div>
            <div className={d.lb}>{today ? "오늘 실사" : "추정 재고"}</div>
            <div className={`${d.v} num ${low ? d.low : ""}`}>
              {row.stock === null ? "–" : fmtStock(row.stock, row.baseUnit)}
              <small>{u}</small>
            </div>
          </div>
          {/* 주기형은 입고 기록으로만 계산해 실사를 받지 않는다 (백엔드 400) */}
          {!counting && row.kind !== "CYCLE" && (
            <Button onClick={() => setCounting(true)}>재고 직접 입력</Button>
          )}
          {counting && (
            <div className={d.cnt}>
              <button
                type="button"
                className={d.step}
                aria-label="줄이기"
                onClick={() => setV(String(Math.max(0, (qty || 0) - 1)))}
              >
                −
              </button>
              <input
                inputMode="decimal"
                value={v}
                autoFocus
                aria-label={`지금 있는 ${row.name} (${u})`}
                onChange={(e) => setV(e.target.value.replace(/[^\d.]/g, ""))}
                onKeyDown={(e) => e.key === "Enter" && save()}
              />
              <span className={d.u}>{u}</span>
              <button
                type="button"
                className={d.step}
                aria-label="늘리기"
                onClick={() => setV(String((qty || 0) + 1))}
              >
                +
              </button>
              <Button disabled={pending || v === ""} onClick={save}>
                저장
              </Button>
            </div>
          )}
        </div>
      </div>
      <div className={d.blk}>
        <dl className={d.kv}>
          <div>
            <dt>안전재고</dt>
            <dd className="num">
              {fmt(safe)} {u}
            </dd>
          </div>
          <div>
            <dt>하루 평균 사용</dt>
            <dd className="num">
              {fmt(avg)} {u}
            </dd>
          </div>
          <div>
            <dt>다 떨어지는 날</dt>
            <dd>{row.stock === null || avg <= 0 ? "–" : `${Math.floor(row.stock / avg)}일 뒤`}</dd>
          </div>
          <div>
            <dt>거래처</dt>
            <dd>{supplierName(suppliers, row.supplierId) || "–"}</dd>
          </div>
          <div>
            <dt>상품</dt>
            <dd>{row.packLabel || "–"}</dd>
          </div>
          <div>
            <dt>단가</dt>
            <dd className="num">{packPriceLabel(row)}</dd>
          </div>
        </dl>
      </div>
      {row.daily.length > 0 && (
        <div className={d.blk}>
          <h3>
            최근 7일 사용량 <span>{u}</span>
          </h3>
          <div className={d.bars}>
            {row.daily.map((x, k) => (
              <div key={days[k] ?? k}>
                <em className="num">{fmt(x)}</em>
                <i
                  style={{
                    height: `${Math.max(4, (x / max) * 72)}%`,
                    animationDelay: `${k * 30}ms`,
                  }}
                />
                <span>{days[k] ? WD[new Date(`${days[k]}T00:00:00Z`).getUTCDay()] : ""}</span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className={d.acts}>
        <Button variant="ghost" onClick={onEdit}>
          정보 고치기
        </Button>
      </div>
    </Modal>
  );
}
