"use client";

import { useState } from "react";
import { AppShell } from "@/components/AppShell";
import { Button } from "@/components/Button";
import { Card } from "@/components/Card";
import { Num } from "@/components/Num";
import { ReviewCell, reviewClass } from "@/components/ReviewRow";
import { Screen } from "@/components/Screen";
import { fmt, won } from "@/lib/format";
import {
  DEMO_INGREDIENT_OPTIONS,
  DEMO_INGREDIENTS,
  DEMO_MATCHES,
  DEMO_MENU_OPTIONS,
  DEMO_RECIPE,
  DEMO_UNMAPPED,
  REVIEW_SHELL,
} from "@/lib/mock/review";
import { resolveReviewRow, reviewCount } from "@/lib/reviewReasons";
import styles from "./page.module.css";

const UPLOADS = [
  { date: "10/5(일) 21:40", file: "매출내역_20251005.xlsx", rows: 1204 },
  { date: "10/4(토) 21:12", file: "매출내역_20251004.xlsx", rows: 1388 },
  { date: "10/3(금) 22:05", file: "매출내역_20251003.xlsx", rows: 1251 },
];

const Icon = ({ d, className }: { d: string; className?: string }) => (
  <svg className={className ?? styles.ic} viewBox="0 0 24 24" aria-hidden="true">
    <path d={d} />
  </svg>
);

// 확인 필요 데모: 시안 #receive · #recipe · #settings · #more 의 칸 · 상태 글자 · ⓘ · 탭 배지
export default function ReviewDemo() {
  const [matches, setMatches] = useState(DEMO_MATCHES);
  const [lines, setLines] = useState(DEMO_RECIPE.lines);
  const [ings, setIngs] = useState(DEMO_INGREDIENTS);
  const [unmapped, setUnmapped] = useState(DEMO_UNMAPPED);
  const countIng = (id: string) => setIngs((p) => resolveReviewRow(p, id));

  return (
    <AppShell
      storeName={REVIEW_SHELL.storeName}
      today={REVIEW_SHELL.today}
      counts={{
        order: 0,
        receiving: reviewCount(matches),
        recipes: reviewCount(lines),
        suppliers: reviewCount(ings),
        more: reviewCount(unmapped),
      }}
    >
      <Screen title="입고">
        <Card
          title={
            <>확인 목록 {matches.length > 0 && <span className="num n17">{matches.length}</span>}</>
          }
        >
          {matches.length === 0 && <p className={styles.empty}>모두 확인됨</p>}
          {matches.map((m) => (
            <div key={m.id} className={styles.match}>
              <div className={styles.matchName}>
                “{m.name}”<small>영수증 {m.receipts}장</small>
              </div>
              <div />
              <div className={styles.matchCtl}>
                <select
                  className={`${styles.sel} ${reviewClass(m.needsReview, "field") ?? ""}`}
                  defaultValue={m.guess || "cycle"}
                  aria-label={`${m.name} 재료`}
                >
                  {DEMO_INGREDIENT_OPTIONS.map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.name}
                    </option>
                  ))}
                  <option value="cycle">주기형 품목</option>
                </select>
                {m.unit && (
                  <span className={styles.matchUnit}>
                    1개 ={" "}
                    <input
                      className={`${styles.qtyIn} num n17 ${reviewClass(m.needsReview, "field") ?? ""}`}
                      type="number"
                      inputMode="decimal"
                      min={0}
                      defaultValue={1000}
                      aria-label="1개당 g"
                    />{" "}
                    g
                  </span>
                )}
                <Button
                  variant="ghost"
                  onClick={() => setMatches((p) => p.filter((x) => x.id !== m.id))}
                >
                  확인
                </Button>
              </div>
            </div>
          ))}
        </Card>
      </Screen>

      <Screen title="레시피">
        <Card>
          <div className={styles.menuHd}>
            <span className={styles.thumb}>
              <svg className={styles.thumbIc} viewBox="0 0 24 24" aria-hidden="true">
                <path d="M3 11h18a9 9 0 0 1-18 0z" />
                <path d="M8 7c0-1.5 1-2 1-3.5M12 7c0-1.5 1-2 1-3.5M16 7c0-1.5 1-2 1-3.5" />
              </svg>
            </span>
            <div>
              <h2>{DEMO_RECIPE.name}</h2>
              <span className={styles.sub}>{won(DEMO_RECIPE.price)}</span>
            </div>
          </div>
          {lines.map((l) => (
            <div key={l.id} className={`${styles.line} ${styles.wide} ${styles.tight}`}>
              <div className={styles.a}>{l.name}</div>
              <div className={styles.b}>
                <input
                  className={`${styles.qtyIn} num n17 ${reviewClass(l.needsReview, "field") ?? ""}`}
                  type="number"
                  inputMode="decimal"
                  min={0}
                  defaultValue={l.qty}
                  aria-label={`${l.name} 1인분 양 (${l.unit})`}
                  onChange={() => l.needsReview && setLines((p) => resolveReviewRow(p, l.id))}
                />{" "}
                <span className={styles.bold}>{l.unit}</span>
              </div>
              <div className={styles.c} />
              <div className={styles.d} />
              <div className={styles.e}>
                <Button
                  variant="quiet"
                  aria-label={`${l.name} 빼기`}
                  onClick={() => setLines((p) => p.filter((x) => x.id !== l.id))}
                >
                  <Icon d="M6 6l12 12M18 6L6 18" />
                </Button>
              </div>
            </div>
          ))}
        </Card>
      </Screen>

      <Screen title="거래처 및 재료">
        <Card title="재료">
          <div className={styles.ingLines}>
            {ings.map((i) => (
              <button
                key={i.id}
                type="button"
                className={styles.ingLine}
                onClick={() => i.needsReview && countIng(i.id)}
              >
                <span className={styles.ingA}>{i.name}</span>
                <span className={styles.ingB}>
                  <ReviewCell needsReview={i.needsReview}>
                    <Num size={17} value={fmt(i.stock)} unit={i.unit} />
                  </ReviewCell>
                </span>
                <span className={styles.ingC}>
                  <span>
                    안전 {fmt(i.safety)} {i.unit}
                  </span>
                  <span>{i.safetyDays}일</span>
                  <span>
                    {i.price.toLocaleString("ko-KR")}원/{i.unit}
                  </span>
                </span>
                <Icon d="M9 6l6 6-6 6" className={styles.chev} />
              </button>
            ))}
          </div>
          <table className={`${styles.table} ${styles.ingTable}`}>
            <thead>
              <tr>
                <th>재료</th>
                <th className={styles.r}>추정 재고</th>
                <th className={styles.r}>안전재고</th>
                <th className={styles.r}>1회 최대</th>
                <th className={styles.r}>단가</th>
                <th>거래처 상품</th>
              </tr>
            </thead>
            <tbody>
              {ings.map((i) => (
                <tr key={i.id}>
                  <td>{i.name}</td>
                  <td className={`${styles.r} ${reviewClass(i.needsReview, "td") ?? ""}`}>
                    {i.needsReview && (
                      <>
                        <Button small variant="ghost" onClick={() => countIng(i.id)}>
                          재고 확인
                        </Button>{" "}
                      </>
                    )}
                    <span className="num n14">
                      {fmt(i.stock)} {i.unit}
                    </span>
                  </td>
                  <td className={styles.r}>
                    <span className="num n14">
                      {fmt(i.safety)} {i.unit}
                    </span>{" "}
                    <span className={styles.dim}>{i.safetyDays}일</span>
                  </td>
                  <td className={`${styles.r} num n14`}>
                    {fmt(i.max)} {i.unit}
                  </td>
                  <td className={styles.r}>
                    <span className="num n14">{i.price.toLocaleString("ko-KR")}</span>
                  </td>
                  <td className={styles.product}>{i.product}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </Screen>

      <Screen title="더보기">
        <Card title="POS 업로드">
          {unmapped.length > 0 && (
            <div className={styles.mapWrap}>
              <div className={styles.subHead}>
                <h2>
                  메뉴 연결 <span className="num n17">{unmapped.length}</span>
                </h2>
                <Button small variant="ghost" onClick={() => setUnmapped([])}>
                  저장
                </Button>
              </div>
              {unmapped.map((u) => (
                <div
                  key={u.id}
                  className={`${styles.line} ${styles.wide} ${styles.tight} ${styles.map}`}
                >
                  <div className={styles.a}>“{u.pos}”</div>
                  <div className={styles.b} />
                  <div className={styles.c} />
                  <div className={styles.d} />
                  <div className={styles.e}>
                    <select
                      className={`${styles.sel} ${reviewClass(u.needsReview, "field") ?? ""}`}
                      defaultValue={u.guess}
                      aria-label={`${u.pos}을 연결할 메뉴`}
                    >
                      {DEMO_MENU_OPTIONS.map((o) => (
                        <option key={o.id} value={o.id}>
                          {o.name}
                        </option>
                      ))}
                      <option value="skip">추적 안 함</option>
                    </select>
                  </div>
                </div>
              ))}
            </div>
          )}
          {UPLOADS.map((u) => (
            <div key={u.file} className={`${styles.line} ${styles.tight}`}>
              <div className={styles.a}>{u.file}</div>
              <div className={styles.b}>
                <Num size={17} value={u.rows.toLocaleString("ko-KR")} unit="행" />
              </div>
              <div className={styles.c}>{u.date}</div>
              <div className={`${styles.d} ${styles.ok}`}>반영 완료</div>
            </div>
          ))}
        </Card>
      </Screen>
    </AppShell>
  );
}
