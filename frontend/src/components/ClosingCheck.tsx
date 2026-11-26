"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useClosingCheck, type Line } from "@/hooks/useClosingCheck";
import {
  REASON_LABEL,
  toBase,
  type ClosingItem,
  type ClosingReason,
  type ParsedLine,
} from "@/lib/closingCheck";
import { fmt } from "@/lib/format";
import type { IngredientApi } from "@/lib/types";
import { FROM_API, shownQty } from "@/lib/units";
import { ActionBar } from "./ActionBar";
import { Button } from "./Button";
import { Card } from "./Card";
import { Tag } from "./Chip";
import { Num } from "./Num";
import { Stepper } from "./Stepper";
import styles from "./ClosingCheck.module.css";

const REASONS = Object.keys(REASON_LABEL) as ClosingReason[];

// 마감 확인 (DECISIONS #88): 확인 품목 → 버린 재료 자유 입력 → 해석 줄 확인 → 저장
export function ClosingCheck() {
  const router = useRouter();
  const c = useClosingCheck();
  const [text, setText] = useState("");

  if (!c.items)
    return c.error ? (
      <p role="alert" className={styles.error}>
        {c.error.message}
      </p>
    ) : null;

  return (
    <>
      {c.items.length > 0 && (
        <Card title="재고 확인">
          <ul className={styles.list}>
            {c.items.map((i) => (
              <ItemRow
                key={i.ingredientId}
                item={i}
                value={c.value(i)}
                onChange={(v) => c.setValue(i.ingredientId, v)}
              />
            ))}
          </ul>
        </Card>
      )}
      <Card title="버린 재료">
        <form
          className={styles.say}
          onSubmit={async (e) => {
            e.preventDefault();
            if (await c.parse(text.trim())) setText("");
          }}
        >
          <textarea
            rows={2}
            value={text}
            onChange={(e) => setText(e.target.value)}
            aria-label="버린 재료"
            placeholder="양파 반 망 버렸어요"
          />
          <Button type="submit" variant="ghost" disabled={c.busy || text.trim() === ""}>
            보내기
          </Button>
        </form>
        {c.lines.length > 0 && (
          <ul className={styles.list}>
            {c.lines.map((l) => (
              <LineRow
                key={l.key}
                line={l}
                ingredients={c.ingredients}
                onChange={(patch) => c.setLine(l.key, patch)}
                onRemove={() => c.removeLine(l.key)}
              />
            ))}
          </ul>
        )}
      </Card>
      {c.error && (
        <p role="alert" className={styles.error}>
          {c.error.message}
        </p>
      )}
      <ActionBar
        disabled={c.busy || !c.ready}
        onClick={async () => {
          if (await c.save()) router.push("/");
        }}
      >
        저장
      </ActionBar>
    </>
  );
}

function ItemRow({
  item,
  value,
  onChange,
}: {
  item: ClosingItem;
  value: number;
  onChange: (v: number) => void;
}) {
  const base = shownQty(toBase(item, value), item.baseUnit);
  return (
    <li className={styles.row}>
      <span className={styles.name}>
        {item.name}
        {item.packUnit && (
          <span className={styles.sub}>
            {base.n}
            {base.u}
          </span>
        )}
      </span>
      {item.packUnit ? (
        <Stepper value={value} step={item.step} unit={item.packUnit} onChange={onChange} />
      ) : (
        <label className={styles.qty}>
          <input
            className="num n17"
            type="number"
            inputMode="decimal"
            min={0}
            step="any"
            value={value}
            onChange={(e) => onChange(Math.max(0, Number(e.target.value)))}
            aria-label={`${item.name} 남은 양`}
          />
          <span>{FROM_API[item.baseUnit]}</span>
        </label>
      )}
    </li>
  );
}

function LineRow({
  line,
  ingredients,
  onChange,
  onRemove,
}: {
  line: Line;
  ingredients: IngredientApi[];
  onChange: (patch: Partial<ParsedLine>) => void;
  onRemove: () => void;
}) {
  const ing = ingredients.find((g) => g.id === line.ingredientId);
  // 단위를 모르는 줄은 기준 단위 숫자로 받는다. 한 번 숫자 칸이 된 줄은 값을 넣어도 숫자 칸으로 남는다
  const [asked] = useState(line.baseQty === null);
  return (
    <li className={styles.row}>
      {line.ingredientId ? (
        <span className={styles.name}>{line.ingredientName}</span>
      ) : (
        <select
          className={`${styles.pick} ${styles.need}`}
          value=""
          onChange={(e) => {
            const g = ingredients.find((x) => x.id === e.target.value);
            if (g) onChange({ ingredientId: g.id, ingredientName: g.name });
          }}
          aria-label="재료"
        >
          <option value="" disabled>
            {line.ingredientName}
          </option>
          {ingredients.map((g) => (
            <option key={g.id} value={g.id}>
              {g.name}
            </option>
          ))}
        </select>
      )}
      <div className={styles.tail}>
        {asked ? (
          <label className={line.baseQty ? styles.qty : `${styles.qty} ${styles.need}`}>
            <input
              className="num n17"
              type="number"
              inputMode="decimal"
              min={0}
              step="any"
              value={line.baseQty ?? ""}
              onChange={(e) =>
                onChange({ baseQty: e.target.value === "" ? null : Number(e.target.value) })
              }
              placeholder={`${fmt(line.qty)}${line.unit}`}
              aria-label={`${line.ingredientName} 양`}
            />
            {ing && <span>{FROM_API[ing.unit]}</span>}
          </label>
        ) : (
          <Num size={17} value={fmt(line.qty)} unit={line.unit} />
        )}
        {line.action === "COUNT" ? (
          <Tag>남은 양</Tag>
        ) : (
          <select
            className={styles.reason}
            value={line.reason}
            onChange={(e) => onChange({ reason: e.target.value as ClosingReason })}
            aria-label="사유"
          >
            {REASONS.map((r) => (
              <option key={r} value={r}>
                {REASON_LABEL[r]}
              </option>
            ))}
          </select>
        )}
        <button
          type="button"
          className={styles.del}
          onClick={onRemove}
          aria-label={`${line.ingredientName} 삭제`}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
    </li>
  );
}
