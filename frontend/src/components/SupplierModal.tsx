"use client";

import { useEffect, useState, useTransition } from "react";
import { getMonthHistory } from "@/lib/api";
import { won } from "@/lib/format";
import { todayYmd } from "@/lib/today";
import { lastOrderDates } from "@/lib/stockPlace";
import { CHANNEL_LABEL, DAYS, DAY_LABEL } from "@/lib/suppliers";
import type {
  DeliveryDay,
  IngredientRow,
  Supplier,
  SupplierChannel,
  SupplierEdit,
  SupplierProductApi,
} from "@/lib/types";
import { Button } from "./Button";
import d from "./Detail.module.css";
import { Modal } from "./Modal";

const CHANNELS = Object.keys(CHANNEL_LABEL) as SupplierChannel[];
const prevMonth = (ym: string) => {
  const [y, m] = ym.split("-").map(Number);
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, "0")}`;
};
const md = (iso: string) => `${Number(iso.slice(5, 7))}월 ${Number(iso.slice(8, 10))}일`;

type Draft = {
  name: string;
  channel: SupplierChannel | "";
  cutoff: string;
  lead: string;
  cycle: string;
  min: string;
  days: DeliveryDay[];
  template: string;
};
const draftOf = (s: Supplier | null): Draft => ({
  name: s?.name ?? "",
  channel: s?.channel ?? "",
  cutoff: s?.cutoff ?? "",
  lead: s?.leadDays == null ? "" : String(s.leadDays),
  cycle: s?.cycleDays == null ? "" : String(s.cycleDays),
  min: s?.minOrder ? String(s.minOrder) : "",
  days: s?.days ?? [],
  template: s?.template ?? "",
});
const num = (v: string) => (v === "" ? undefined : Number(v));

// 거래처 모달 (시안 stock_v3.html). 고치기 · 추가는 같은 배치에서 글자 자리가 입력칸으로 바뀐다
export function SupplierModal({
  supplier,
  products,
  ingredients,
  onSave,
  onRemove,
  onClose,
}: {
  // null = 추가
  supplier: Supplier | null;
  products: SupplierProductApi[];
  ingredients: IngredientRow[];
  onSave: (e: SupplierEdit) => Promise<boolean>;
  onRemove?: () => Promise<boolean>;
  onClose: () => void;
}) {
  const s = supplier;
  const [edit, setEdit] = useState(!s);
  const [v, setV] = useState(() => draftOf(s));
  const [last, setLast] = useState<Record<string, string>>({});
  const [pending, start] = useTransition();
  const set = (k: keyof Draft) => (e: { target: { value: string } }) =>
    setV({ ...v, [k]: e.target.value });

  // 시켰던 재료의 가장 최근 주문일: 이번 달 · 지난달 발주 이력에서 찾는다
  useEffect(() => {
    if (!s) return;
    let live = true;
    const ym = todayYmd().slice(0, 7);
    Promise.all([getMonthHistory(ym), getMonthHistory(prevMonth(ym))]).then((rs) => {
      const lines = rs.flatMap((r) => (r.success ? r.data.lines : []));
      if (live) setLast(lastOrderDates(lines, s.name));
    });
    return () => {
      live = false;
    };
  }, [s]);

  const items = s
    ? products
        .filter((p) => p.supplierId === s.id)
        .map((p) => ({
          p,
          name: ingredients.find((i) => i.id === p.ingredientId)?.name ?? p.productName,
        }))
    : [];

  const save = () =>
    start(async () => {
      if (!v.name.trim()) return;
      const ok = await onSave({
        name: v.name,
        channel: v.channel || null,
        cutoff: v.cutoff,
        leadDays: num(v.lead),
        cycleDays: num(v.cycle),
        minOrder: num(v.min) ?? 0,
        days: v.days,
        template: v.template,
      });
      if (!ok) return;
      if (s) setEdit(false);
      else onClose();
    });

  const box = (k: "lead" | "cycle" | "min", unit: string) => (
    <span className={d.edw}>
      <input
        className={`${d.ed} num`}
        type="number"
        min={0}
        inputMode="numeric"
        value={v[k]}
        onChange={set(k)}
      />
      <small>{unit}</small>
    </span>
  );

  return (
    <Modal
      open
      label={s?.name ?? "거래처 추가"}
      onClose={onClose}
      head={
        edit ? (
          <input
            className={`${d.ed} ${d.name}`}
            value={v.name}
            maxLength={100}
            placeholder="거래처 이름"
            aria-label="거래처 이름"
            autoFocus={!s}
            onChange={set("name")}
          />
        ) : (
          <h2>{s?.name}</h2>
        )
      }
    >
      <div className={d.blk}>
        <dl className={d.kv}>
          <div>
            <dt>주문 마감</dt>
            <dd className="num">
              {edit ? (
                <input
                  className={`${d.ed} num`}
                  type="time"
                  value={v.cutoff}
                  onChange={set("cutoff")}
                />
              ) : (
                s?.cutoff || "–"
              )}
            </dd>
          </div>
          <div className={edit ? d.wide : undefined}>
            <dt>배송 요일</dt>
            <dd>
              {edit ? (
                <span className={d.days}>
                  {DAYS.map((x) => (
                    <button
                      key={x}
                      type="button"
                      aria-pressed={v.days.includes(x)}
                      onClick={() =>
                        setV({
                          ...v,
                          days: v.days.includes(x) ? v.days.filter((y) => y !== x) : [...v.days, x],
                        })
                      }
                    >
                      {DAY_LABEL[x]}
                    </button>
                  ))}
                </span>
              ) : (
                s?.days.map((x) => DAY_LABEL[x]).join(" · ") || "–"
              )}
            </dd>
          </div>
          <div>
            <dt>배송 기간</dt>
            <dd>{edit ? box("lead", "일") : s?.leadDays == null ? "–" : `${s.leadDays}일`}</dd>
          </div>
          <div>
            <dt>주문 간격</dt>
            <dd>{edit ? box("cycle", "일") : s?.cycleDays == null ? "–" : `${s.cycleDays}일`}</dd>
          </div>
          <div>
            <dt>최소 주문금액</dt>
            <dd className="num">
              {edit ? box("min", "원") : s?.minOrder ? won(s.minOrder) : "없음"}
            </dd>
          </div>
          <div>
            <dt>주문 방법</dt>
            <dd>
              {edit ? (
                <select
                  className={d.ed}
                  value={v.channel}
                  onChange={set("channel")}
                  aria-label="주문 방법"
                >
                  <option value="" />
                  {CHANNELS.map((c) => (
                    <option key={c} value={c}>
                      {CHANNEL_LABEL[c]}
                    </option>
                  ))}
                </select>
              ) : s?.channel ? (
                CHANNEL_LABEL[s.channel]
              ) : (
                "–"
              )}
            </dd>
          </div>
        </dl>
      </div>
      {items.length > 0 && (
        <div className={d.blk}>
          <h3>
            시켰던 재료 {items.length}
            <span>최근 주문일</span>
          </h3>
          {items.map(({ p, name }) => (
            <div key={p.id} className={d.r}>
              <span>
                <b>{name}</b>
                <small>{p.productName}</small>
              </span>
              <span className="num">
                {p.price == null
                  ? "–"
                  : `${p.price.toLocaleString("ko-KR")}원/1${p.packUnit || "개"}`}
              </span>
              <span className={d.when}>{last[name] ? md(last[name]) : "–"}</span>
            </div>
          ))}
        </div>
      )}
      {edit ? (
        <div className={d.blk}>
          <h3>주문 메시지</h3>
          <textarea
            className={`${d.ed} ${d.msgEd}`}
            rows={4}
            value={v.template}
            aria-label="주문 메시지"
            onChange={set("template")}
          />
        </div>
      ) : (
        s?.template && (
          <div className={d.blk}>
            <h3>주문 메시지</h3>
            <div className={d.msg}>{s.template}</div>
          </div>
        )
      )}
      <div className={d.acts}>
        {edit ? (
          <>
            {onRemove && (
              <Button
                variant="ghost"
                className={d.del}
                disabled={pending}
                onClick={() => {
                  if (window.confirm(`${s?.name}을(를) 삭제할까요?`))
                    start(async () => {
                      if (await onRemove()) onClose();
                    });
                }}
              >
                삭제
              </Button>
            )}
            <span className={d.grow} />
            <Button
              variant="ghost"
              disabled={pending}
              onClick={() => {
                if (!s) return onClose();
                setV(draftOf(s));
                setEdit(false);
              }}
            >
              취소
            </Button>
            <Button disabled={pending || !v.name.trim()} onClick={save}>
              {s ? "저장" : "추가"}
            </Button>
          </>
        ) : (
          <Button
            variant="ghost"
            onClick={() => {
              setV(draftOf(s));
              setEdit(true);
            }}
          >
            정보 고치기
          </Button>
        )}
      </div>
    </Modal>
  );
}
