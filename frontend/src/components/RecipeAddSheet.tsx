"use client";

import { useState, type FormEvent } from "react";
import type { RecipeLineDraft, RecipePick, RecipeUnit } from "@/lib/types";
import { BottomSheet } from "./BottomSheet";
import { Button } from "./Button";
import { Field, FieldGrid } from "./Field";
import styles from "./RecipeAddSheet.module.css";

const NEW = "__new";
const UNITS: RecipeUnit[] = ["g", "ml", "개"];

// 시안 #addDialog: 재료 고르기 또는 '새 재료'. 기존 재료는 단위가 그 재료 단위로 고정 (DECISIONS #33)
function AddForm({
  free,
  onAdd,
}: {
  free: RecipePick[];
  onAdd: (i: RecipeLineDraft) => void;
}) {
  const [sel, setSel] = useState(free[0]?.id ?? NEW);
  const [name, setName] = useState("");
  const [qty, setQty] = useState("");
  const [unit, setUnit] = useState<RecipeUnit>("g");
  const isNew = sel === NEW;
  const picked = free.find((i) => i.id === sel);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const q = Number(qty);
    if (!(q > 0)) return;
    if (!isNew) return onAdd({ ingredientId: sel, qty: q });
    if (name.trim()) onAdd({ name: name.trim(), unit, qty: q });
  };

  return (
    <form onSubmit={submit} className={styles.form}>
      <Field label="재료">
        <select value={sel} onChange={(e) => setSel(e.target.value)}>
          {free.map((i) => (
            <option key={i.id} value={i.id}>
              {i.name}
            </option>
          ))}
          <option value={NEW}>새 재료</option>
        </select>
      </Field>
      {isNew && (
        <Field label="새 재료 이름">
          <input
            type="text"
            required
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
      )}
      <FieldGrid>
        <Field label="1인분 양">
          <input
            type="number"
            inputMode="decimal"
            min={0}
            step="any"
            required
            value={qty}
            onChange={(e) => setQty(e.target.value)}
          />
        </Field>
        <Field label="단위">
          <select
            value={isNew ? unit : (picked?.unit ?? unit)}
            disabled={!isNew}
            onChange={(e) => setUnit(e.target.value as RecipeUnit)}
          >
            {UNITS.map((u) => (
              <option key={u}>{u}</option>
            ))}
          </select>
        </Field>
      </FieldGrid>
      <Button type="submit" block>
        추가
      </Button>
    </form>
  );
}

export function RecipeAddSheet({
  open,
  free,
  onAdd,
  onClose,
}: {
  open: boolean;
  free: RecipePick[];
  onAdd: (i: RecipeLineDraft) => void;
  onClose: () => void;
}) {
  return (
    <BottomSheet open={open} title="재료 추가" onClose={onClose}>
      {open && <AddForm free={free} onAdd={onAdd} />}
    </BottomSheet>
  );
}
