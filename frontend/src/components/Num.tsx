import type { ReactNode } from "react";

// 숫자 네 단계 (DESIGN.md). 크기는 globals.css 의 .n40 .n24 .n17 .n14, 단위는 .unit
export type NumSize = 40 | 24 | 17 | 14;

export function Num({ size, value, unit }: { size: NumSize; value: ReactNode; unit?: string }) {
  return (
    <span className={`num n${size}`}>
      {value}
      {unit && <span className="unit">{unit}</span>}
    </span>
  );
}
