import { fmt, won } from "@/lib/format";
import { Stepper } from "./Stepper";

// 발주 수량: 포장 단위로 오르내리고 옆에 포장 × 개수, 금액
export function QtyStepper({
  value,
  onChange,
  pack,
  packLabel,
  unit,
  unitPrice,
  max,
  noteFull,
}: {
  value: number;
  onChange: (next: number) => void;
  pack: number;
  packLabel: string;
  unit: string;
  unitPrice: number;
  max: number;
  noteFull?: boolean;
}) {
  return (
    <Stepper
      value={value}
      step={pack}
      unit={unit}
      min={pack}
      max={max}
      onChange={onChange}
      noteFull={noteFull}
      note={`${packLabel} × ${fmt(value / pack)}, ${won(value * unitPrice)}`}
    />
  );
}
