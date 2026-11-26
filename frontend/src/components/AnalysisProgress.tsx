"use client";

import { useRouter } from "next/navigation";
import { useAnalysis } from "@/hooks/useAnalysis";
import { ActionBar } from "./ActionBar";
import { Button } from "./Button";
import { Card } from "./Card";
import { FileButton } from "./FileButton";
import { Num } from "./Num";
import { ProgressBar } from "./ProgressBar";
import styles from "./AnalysisProgress.module.css";

// 시안 #analyze · #analyze-fail · #analyze-empty: 전체 진행 카드 → 메뉴 줄(대기 · 정리 중 · 재료 N개)
// → 실패 카드 또는 메뉴판 사진 버튼 → 완료면 하단 '레시피 확인'. 메뉴별 진행 바는 두지 않는다 (#44).
export function AnalysisProgress({ jobId }: { jobId: string | null }) {
  const router = useRouter();
  const a = useAnalysis(jobId);

  return (
    <>
      {a.error && (
        <p role="alert" className={styles.error}>
          {a.error.message}
        </p>
      )}
      <Card>
        <div className={styles.prog}>
          <span className={styles.label}>{a.phase}</span>
          <Num size={24} value={Math.round(a.progress * 100)} unit="%" />
        </div>
        <ProgressBar lg short={a.failed} value={a.progress} label={a.phase} />
      </Card>

      {!a.failed && !a.needsMenuPhoto && a.menus.length > 0 && (
        <Card>
          <div>
            {a.menus.map((m, i) => (
              <div key={m.id} className={styles.mline}>
                <span className={styles.name}>{m.name}</span>
                <span className={a.done ? `${styles.n} ${styles.ok}` : styles.n}>
                  {a.labels[i]}
                </span>
              </div>
            ))}
          </div>
        </Card>
      )}

      {a.failed && (
        <Card className={styles.fail}>
          <b>메뉴를 가져오지 못함</b>
          <Button variant="ghost" disabled={a.busy} onClick={a.retry}>
            <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
              <path d="M20 12a8 8 0 1 1-2.3-5.7" />
              <path d="M20 4v5h-5" />
            </svg>
            다시 시도
          </Button>
        </Card>
      )}

      {a.needsMenuPhoto && (
        <FileButton variant="primary" block accept="image/*" disabled={a.busy} onFile={a.upload}>
          <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 8h3l2-3h6l2 3h3v11H4z" />
            <circle cx="12" cy="13" r="3.5" />
          </svg>
          메뉴판 사진 올리기
        </FileButton>
      )}

      {a.done && (
        <ActionBar onClick={() => router.push("/onboarding/recipes")}>레시피 확인</ActionBar>
      )}
    </>
  );
}
