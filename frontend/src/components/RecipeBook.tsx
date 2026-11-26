"use client";

import Image from "next/image";
import { useState } from "react";
import { useRecipeBook } from "@/hooks/useRecipeBook";
import { qtyToSave } from "@/lib/recipes";
import { Button } from "./Button";
import { Card } from "./Card";
import { RecipeAddSheet } from "./RecipeAddSheet";
import { reviewClass } from "./ReviewRow";
import { Screen } from "./Screen";
import { Skeleton, SkTitle, SK_W } from "./Skeleton";
import styles from "./RecipeBook.module.css";

const Icon = ({ d }: { d: string }) => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d={d} />
  </svg>
);

function Thumb({
  name,
  photoUrl,
  large,
}: {
  name: string;
  photoUrl: string | null;
  large?: boolean;
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  return (
    <span className={large ? `${styles.thumb} ${styles.thumbLg}` : styles.thumb}>
      {photoUrl && photoUrl !== failedUrl ? (
        <Image
          src={photoUrl}
          alt={name}
          width={128}
          height={128}
          unoptimized
          referrerPolicy="no-referrer"
          onError={() => setFailedUrl(photoUrl)}
        />
      ) : (
        <Icon d="M3.5 11h17a8.5 8.5 0 0 1-17 0zM8 7.5c0-1 1-1.5 1-2.5M12 7.5c0-1 1-1.5 1-2.5M16 7.5c0-1 1-1.5 1-2.5" />
      )}
    </span>
  );
}

const won = (n: number | null) => (n === null ? "" : `${n.toLocaleString("ko-KR")}원`);

// 시안 app_all.html 레시피: 메뉴 목록 | 메뉴 상세(어두운 머리 + 재료 × 1인분 양) + 재료 추가 시트.
// 데스크톱은 두 칸, 폰은 목록 → 상세
export function RecipeBook() {
  const r = useRecipeBook();
  const { menu, tab } = r;

  if (!tab)
    return r.error ? (
      <Screen title="레시피">
        <p className={styles.empty}>{r.error}</p>
      </Screen>
    ) : (
      <RecipesLoading />
    );

  // 확인 안 한 메뉴를 위에 묶고, 확인한 메뉴는 아래에 (딱지 대신 순서로 보인다)
  const todo = tab.menus.filter((m) => m.needsReview);
  const groups = [
    { key: "todo", head: "레시피를 확인해주세요!", menus: todo },
    { key: "done", head: "확인완료", menus: tab.menus.filter((m) => !m.needsReview) },
  ].filter((g) => g.menus.length > 0);

  return (
    <Screen title="레시피">
      <div className={styles.rcp} data-open={r.opened || undefined}>
        <Card title="메뉴" className={styles.menus}>
          {r.error && <p className={styles.empty}>{r.error}</p>}
          {groups.map((g) => (
            <div key={g.key} className={styles.menuL}>
              <p className={`${styles.groupHead} ${g.key === "done" ? styles.confirmed : ""}`}>
                {g.head}
              </p>
              {g.menus.map((m) => (
                <button
                  key={m.id}
                  type="button"
                  className={styles.menu}
                  aria-current={m.id === menu?.id || undefined}
                  onClick={() => r.open(m.id)}
                >
                  <Thumb name={m.name} photoUrl={m.photoUrl} />
                  <span>
                    <span className={styles.menuName}>{m.name}</span>
                    <span className={styles.meta}>
                      <span>{won(m.price)}</span>
                      <span>재료 {m.lines.length}</span>
                    </span>
                  </span>
                  <Icon d="M9 5l7 7-7 7" />
                </button>
              ))}
            </div>
          ))}
        </Card>
        {menu && (
          <section className={styles.detail}>
            <Button variant="quiet" className={styles.back} onClick={r.back}>
              <Icon d="M15 5l-7 7 7 7" />
              메뉴
            </Button>
            <div className={styles.top}>
              <Thumb key={menu.id} name={menu.name} photoUrl={menu.photoUrl} large />
              <div>
                <h2>{menu.name}</h2>
                <p className="num">{won(menu.price)}</p>
              </div>
              <div className={styles.cnt}>
                <b className="num">{menu.lines.length}</b>
                <small>재료</small>
              </div>
            </div>
            <div className={styles.rows}>
              {menu.lines.length === 0 && <p className={styles.empty}>재료 없음</p>}
              {menu.lines.map((ing) => (
                <div key={ing.id} className={styles.row}>
                  <span className={styles.name}>{ing.name}</span>
                  <span className={styles.qty}>
                    <input
                      // 다른 메뉴에서 고친 값이 바로 보이게 값을 key 로 쓴다.
                      // 메뉴가 바뀌면 새로 그려져 확인 전 칸 흔들림(.nudge)이 다시 돈다
                      key={`${menu.id}-${ing.qty}`}
                      type="number"
                      inputMode="decimal"
                      min={0}
                      className={`num ${ing.estimated ? `${reviewClass(true, "field")} ${styles.nudge}` : ""}`}
                      // 확인 전 줄은 빈칸 + 추정값 placeholder
                      defaultValue={ing.estimated ? "" : ing.qty}
                      placeholder={ing.estimated ? String(ing.qty) : undefined}
                      aria-label={`${ing.name} 1인분 양 (${ing.unit})`}
                      onBlur={(e) => {
                        const v = qtyToSave(e.target.value, ing);
                        if (v !== null) r.edit(ing.id, v);
                      }}
                    />
                    {ing.unit}
                  </span>
                  <Button
                    variant="quiet"
                    className={styles.del}
                    aria-label={`${ing.name} 빼기`}
                    onClick={() => r.remove(ing.id)}
                  >
                    <Icon d="M6 6l12 12M18 6L6 18" />
                  </Button>
                </div>
              ))}
            </div>
            <div className={styles.add}>
              <Button variant="ghost" onClick={r.openAdd}>
                <Icon d="M12 5v14M5 12h14" />
                재료 추가
              </Button>
            </div>
          </section>
        )}
      </div>
      <RecipeAddSheet open={r.adding} free={r.free} onAdd={r.add} onClose={r.closeAdd} />
    </Screen>
  );
}

// 불러오는 동안: 메뉴 목록 | 메뉴 상세 (폰은 목록만). 글자 없이 막대만
export function RecipesLoading() {
  return (
    <Screen title={<SkTitle w="3.2em" />}>
      <div className={styles.rcp} aria-busy="true">
        <Card className={styles.menus}>
          <Skeleton w="3em" h="1.2em" />
          <div className={styles.menuL}>
            {SK_W.map((w) => (
              <div key={w} className={styles.menu}>
                <Skeleton w="var(--thumb-sm)" h="var(--thumb-sm)" r="var(--r-panel)" />
                <span>
                  <Skeleton w={w} h="1.1em" />
                </span>
              </div>
            ))}
          </div>
        </Card>
        <section className={styles.detail}>
          <Skeleton w="100%" h="120px" r="var(--r-card)" />
          <div className={styles.rows}>
            {SK_W.slice(0, 4).map((w) => (
              <div key={w} className={styles.row}>
                <Skeleton w={w} h="1.1em" />
                <Skeleton w="4em" h="2em" r="var(--r-input)" />
              </div>
            ))}
          </div>
        </section>
      </div>
    </Screen>
  );
}
