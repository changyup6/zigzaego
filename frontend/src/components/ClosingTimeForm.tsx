"use client";

import { useState } from "react";
import { DAYS, toClosingTimes } from "@/lib/closingTime";
import type { ClosingTime } from "@/lib/types";
import { ActionBar } from "./ActionBar";
import { Card } from "./Card";
import { Chip } from "./Chip";
import { ClosingTimePicker } from "./ClosingTimePicker";
import styles from "./ClosingTimeForm.module.css";

// 온보딩 마감 시각 (DECISIONS #88): 시각 하나 + 영업 요일. 건너뛸 수 없다
export function ClosingTimeForm({
  onSave,
}: {
  onSave: (times: ClosingTime[]) => Promise<boolean>;
}) {
  const [time, setTime] = useState("");
  const [open, setOpen] = useState<boolean[]>(() => Array(7).fill(true));
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const times = toClosingTimes(time, open);

  return (
    <>
      <Card>
        <div className={styles.form}>
          <ClosingTimePicker value={time} onChange={setTime} />
          <div className={styles.group} role="group" aria-labelledby="open-days">
            <span id="open-days">영업 요일</span>
            <div className={styles.days}>
              {DAYS.map((d, i) => (
                <Chip
                  key={d}
                  selected={open[i]}
                  onClick={() => setOpen(open.map((on, k) => (k === i ? !on : on)))}
                >
                  {d}
                </Chip>
              ))}
            </div>
          </div>
        </div>
      </Card>
      {failed && (
        <p role="alert" className={styles.error}>
          저장 실패
        </p>
      )}
      <ActionBar
        disabled={busy || times.length === 0}
        onClick={async () => {
          setBusy(true);
          setFailed(!(await onSave(times)));
          setBusy(false);
        }}
      >
        다음
      </ActionBar>
    </>
  );
}
