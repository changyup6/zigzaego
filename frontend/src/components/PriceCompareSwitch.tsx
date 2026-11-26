"use client";

import { useState } from "react";
import { setPriceCompare } from "@/lib/api";
import { Card } from "./Card";
import { Switch } from "./Switch";

// 끄면 품목 상세의 식봄 · 쿠팡 줄이 사라진다 (DECISIONS #48). PATCH /stores/me { priceCompare }, 실패하면 되돌린다
export function PriceCompareSwitch({ on }: { on: boolean }) {
  const [value, setValue] = useState(on);
  return (
    <Card title="가격 비교">
      <Switch
        label="식봄, 쿠팡 가격 비교"
        checked={value}
        onChange={async (e) => {
          const next = e.target.checked;
          setValue(next);
          if (!(await setPriceCompare(next)).success) setValue(!next);
        }}
      />
    </Card>
  );
}
