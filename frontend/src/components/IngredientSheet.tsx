"use client";

import { useState, useTransition } from "react";
import { fmt } from "@/lib/format";
import { KIND_LABEL, STORAGE_LABEL, Z, isLocked, safetyStock, shownUnit } from "@/lib/suppliers";
import type {
  IngredientEdit,
  IngredientKind,
  IngredientRow,
  RecipeUnit,
  StorageType,
  Supplier,
} from "@/lib/types";
import { fmtStock } from "@/lib/units";
import { BottomSheet } from "./BottomSheet";
import { Button } from "./Button";
import { Field, FieldGrid } from "./Field";
import styles from "./IngredientSheet.module.css";

const entries = <K extends string>(labels: Record<K, string>) =>
  (Object.keys(labels) as K[]).map((k) => [k, labels[k]] as const);

const UNITS: RecipeUnit[] = ["g", "ml", "개"];

// 시안 #ingDialog + 보관 방식 · 단위 · 종류 (DECISIONS #26 · #28 · #33). 시안에 없는 칸은 같은 입력 모양으로 아래에 둔다
export function IngredientSheet({
  ing,
  suppliers,
  onClose,
  onSave,
}: {
  ing: IngredientRow;
  suppliers: Supplier[];
  onClose: () => void;
  onSave: (row: IngredientRow, e: IngredientEdit) => Promise<boolean>;
}) {
  const [safetyDays, setSafetyDays] = useState(String(ing.safetyDays));
  const [maxQty, setMaxQty] = useState(String(ing.maxQty ?? ""));
  const [supplierId, setSupplierId] = useState(ing.supplierId);
  const [packLabel, setPackLabel] = useState(ing.packLabel);
  const [unitPrice, setUnitPrice] = useState(String(ing.unitPrice));
  const [storage, setStorage] = useState<StorageType>(ing.storage);
  const [baseUnit, setBaseUnit] = useState<RecipeUnit>(ing.baseUnit);
  const [kind, setKind] = useState<IngredientKind>(ing.kind);
  const [pending, start] = useTransition();
  const u = shownUnit(baseUnit);
  const days = Number(safetyDays);

  const submit = () =>
    start(async () => {
      const num = (v: string, fallback: number) =>
        v !== "" && Number(v) >= 0 ? Number(v) : fallback;
      const ok = await onSave(ing, {
        safetyDays: num(safetyDays, ing.safetyDays),
        maxQty: maxQty === "" ? ing.maxQty : num(maxQty, ing.maxQty ?? 0),
        supplierId,
        packLabel: packLabel.trim() || ing.packLabel,
        unitPrice: num(unitPrice, ing.unitPrice),
        storage,
        kind,
        baseUnit,
      });
      if (ok) onClose();
    });

  return (
    <BottomSheet open title={ing.name} onClose={onClose}>
      <form className={styles.form} action={submit}>
        <dl className={styles.facts}>
          <dt>추정 재고</dt>
          <dd className={`num n14 ${ing.needsReview ? styles.red : ""}`}>
            {ing.stock === null ? "-" : `${fmtStock(ing.stock, ing.baseUnit)} ${u}`}
          </dd>
          <dt>안전재고</dt>
          <dd className="num n14">
            {fmt(safetyStock(ing.daily, days >= 0 ? days : ing.safetyDays))} {u}
          </dd>
          <dt>안전재고 z</dt>
          <dd className="num n14">{Z[storage]}</dd>
        </dl>
        <FieldGrid>
          <Field label="안전재고 (일)">
            <input
              type="number"
              inputMode="decimal"
              min={0}
              step={0.5}
              value={safetyDays}
              onChange={(e) => setSafetyDays(e.target.value)}
            />
          </Field>
          <Field label={`1회 최대 (${u})`}>
            <input
              type="number"
              inputMode="decimal"
              min={0}
              value={maxQty}
              onChange={(e) => setMaxQty(e.target.value)}
            />
          </Field>
          <Field label="거래처">
            <select value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
              {!supplierId && <option value="" />}
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="상품">
            <input type="text" value={packLabel} onChange={(e) => setPackLabel(e.target.value)} />
          </Field>
          <Field label={`단가 (원/${u})`}>
            <input
              type="number"
              inputMode="numeric"
              min={0}
              step={10}
              value={unitPrice}
              onChange={(e) => setUnitPrice(e.target.value)}
            />
          </Field>
          <Field label="보관 방식">
            <select value={storage} onChange={(e) => setStorage(e.target.value as StorageType)}>
              {entries(STORAGE_LABEL).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </Field>
          <Field label="단위">
            <select
              value={baseUnit}
              disabled={isLocked(ing)}
              onChange={(e) => setBaseUnit(e.target.value as RecipeUnit)}
            >
              {UNITS.map((x) => (
                <option key={x}>{x}</option>
              ))}
            </select>
          </Field>
          <Field label="종류">
            <select value={kind} onChange={(e) => setKind(e.target.value as IngredientKind)}>
              {entries(KIND_LABEL)
                .filter(([k]) => k !== "CYCLE" || ing.kind === "CYCLE")
                .map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
            </select>
          </Field>
        </FieldGrid>
        <Button type="submit" block disabled={pending}>
          저장
        </Button>
      </form>
    </BottomSheet>
  );
}
