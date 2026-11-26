import Link from "next/link";
import type { SetupStep } from "@/lib/types";
import styles from "./SetupChecklist.module.css";

const Check = () => (
  <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
    <path d="M5 12l5 5L20 7" />
  </svg>
);

// 시안 app_all.html 가게 설정 (어두운 카드): 끝난 단계는 빨간 체크 · 흐린 글자 · 누를 수 없음. 다 끝나면 사라진다
export function SetupChecklist({ steps }: { steps: SetupStep[] }) {
  const done = steps.filter((s) => s.done).length;
  if (done === steps.length) return null;
  return (
    <section id="setup" className={styles.card} aria-labelledby="setup-h">
      <div className={styles.head}>
        <h2 id="setup-h">가게 설정</h2>
        <span className="num">
          {done}
          <em>/{steps.length}</em>
        </span>
      </div>
      <ul className={styles.list}>
        {steps.map((s) => {
          const inner = (
            <>
              <span>
                <b>{s.name}</b>
                {s.where && <small>{s.where}</small>}
              </span>
              <span className={styles.ck}>
                <Check />
              </span>
            </>
          );
          return (
            <li key={s.id}>
              {s.done ? (
                <span className={`${styles.row} ${styles.done}`} aria-disabled="true">
                  {inner}
                </span>
              ) : (
                <Link href={s.href} className={styles.row}>
                  {inner}
                </Link>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
