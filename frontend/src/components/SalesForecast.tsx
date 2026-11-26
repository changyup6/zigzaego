"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { getSalesForecast } from "@/lib/api";
import { fmt } from "@/lib/format";
import type { SalesForecast as Forecast } from "@/lib/types";
import { vt } from "@/lib/viewTransition";
import { Skeleton } from "./Skeleton";
import { Button } from "./Button";
import styles from "./SalesForecast.module.css";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");
const subscribe = () => () => {};
const reduce = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const phone = () => window.matchMedia("(max-width: 760px)").matches;

// 감쇠 스프링(ζ=0.8, 넘침 약 1.5%)을 CSS linear() 로 근사
function spring(z = 0.8, n = 40) {
  const wd = Math.sqrt(1 - z * z);
  const T = Math.log(400) / z;
  const pts = Array.from({ length: n + 1 }, (_, i) => {
    const t = (i / n) * T;
    return i === n ? 1 : 1 - Math.exp(-z * t) * (Math.cos(wd * t) + (z / wd) * Math.sin(wd * t));
  });
  return `linear(${pts.map((v) => v.toFixed(4)).join(",")})`;
}
const SPRING = spring();
const EASE_OUT = "cubic-bezier(.23,1,.32,1)";

const clipOf = (r: DOMRect, round: string) =>
  `inset(${r.top}px ${innerWidth - r.right}px ${innerHeight - r.bottom}px ${r.left}px round ${round})`;

// 위젯에 있던 것 → 모달의 같은 것. 글자는 비율 유지(keep), 막대는 가로세로 따로
function from(a: Element, b: Element, keep: boolean) {
  const A = a.getBoundingClientRect();
  const B = b.getBoundingClientRect();
  const sx = keep ? A.height / B.height : A.width / B.width;
  return `translate(${A.left - B.left}px,${A.top - B.top}px) scale(${sx},${A.height / B.height})`;
}

const Arrow = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true" style={vt("sales-arrow")}>
    <path d="M7 17L17 7M9 7h8v8" />
  </svg>
);

// 예측을 받기 전: 카드 크기만 두고 빨간 바탕 대신 스켈레톤 막대. 화면별 로딩 화면에서도 쓴다
export function SalesForecastEmpty({ mini = false }: { mini?: boolean }) {
  return (
    <div
      className={cx(styles.card, mini && styles.mini, styles.empty)}
      style={{ display: "grid", grid: "1fr / 1fr", padding: 0, background: "none" }}
      aria-hidden="true"
    >
      <Skeleton h="100%" r="inherit" />
    </div>
  );
}

function useForecast() {
  const [f, setF] = useState<Forecast | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    getSalesForecast().then((r) => {
      if (!live) return;
      setF(r.success ? r.data : null);
      setFailed(!r.success);
    });
    return () => {
      live = false;
    };
  }, [attempt]);
  return {
    f,
    failed,
    retry: () => {
      setF(null);
      setFailed(false);
      setAttempt((n) => n + 1);
    },
  };
}

function ForecastNotice({
  mini,
  failed = false,
  onRetry,
}: {
  mini: boolean;
  failed?: boolean;
  onRetry: () => void;
}) {
  return (
    <div className={styles.notice} data-mini={mini || undefined} role="status">
      <p>{failed ? "매출 예측을 불러오지 못했어요" : "아직 매출 예측이 없어요"}</p>
      <Button small variant="ghost" onClick={onRetry}>
        다시 불러오기
      </Button>
    </div>
  );
}

// 이번 주 매출 예측 카드 (시안 order_v5.html .sales · home_v3.html .ev).
// 누르면 카드가 그대로 커져 모달이 된다: 예측 바탕을 clip-path 로 카드 사각형 → 모달 사각형까지 펼치고,
// 숫자 · 이유 · 막대는 같은 자리에서 커진다(FLIP). 분석 문장은 다 펼친 뒤 타이핑.
// mini = 홈 인사말 옆 작은 카드 (이유 줄 · 요일 글자 없음)
export function SalesForecast({ mini = false }: { mini?: boolean }) {
  const { f, failed, retry } = useForecast();
  const mounted = useSyncExternalStore(
    subscribe,
    () => true,
    () => false,
  );
  const card = useRef<HTMLButtonElement>(null);
  const fx = useRef<HTMLDivElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const label = useRef<HTMLSpanElement>(null);
  const fxLabel = useRef<HTMLDivElement>(null);
  const num = useRef<HTMLSpanElement>(null);
  const why = useRef<HTMLSpanElement>(null);
  const bars = useRef<HTMLDivElement>(null);
  const fxNum = useRef<HTMLHeadingElement>(null);
  const fxWhy = useRef<HTMLDivElement>(null);
  const fxBars = useRef<HTMLDivElement>(null);
  const [shown, setShown] = useState(0);
  const typing = useRef<number | undefined>(undefined);
  const ready = useRef<number | undefined>(undefined);
  const motion = useRef<Animation[]>([]);

  const text = f?.analysis ?? "";
  const max = f ? Math.max(1, ...f.days.map((d) => d.value)) : 1;
  const hot = f ? f.days.findIndex((d) => d.value === max) : -1;

  const pairs = (): [Element, Element, boolean][] => {
    const wb = [...(bars.current?.querySelectorAll("i") ?? [])];
    const mb = [...(fxBars.current?.querySelectorAll("i") ?? [])];
    const p: [Element, Element, boolean][] = [[num.current!, fxNum.current!, true]];
    if (why.current) p.push([why.current, fxWhy.current!, true]);
    return [...p, ...wb.map((b, k): [Element, Element, boolean] => [b, mb[k], false])];
  };

  // 배경뿐 아니라 함께 움직이는 숫자 · 단위 · 이유 · 막대도 카드 색에서 모달 색으로 잇는다.
  const colorPairs = (): (readonly [Element, Element, "color" | "backgroundColor"])[] => [
    [card.current!, fx.current!, "backgroundColor"],
    [card.current!, fxLabel.current!, "backgroundColor"],
    [label.current!, fxLabel.current!, "color"],
    [num.current!, fxNum.current!, "color"],
    [num.current!.querySelector("small")!, fxNum.current!.querySelector("small")!, "color"],
    ...(why.current ? [[why.current, fxWhy.current!, "color"] as const] : []),
    ...pairs()
      .slice(why.current ? 2 : 1)
      .map(([a, b]) => [a, b, "backgroundColor"] as const),
  ];
  const stopMotion = () => {
    clearTimeout(ready.current);
    motion.current.forEach((a) => a.cancel());
    motion.current = [];
  };

  // 모달 상자를 카드의 오른쪽 위 모서리에 붙인다. 오른쪽 칸 = 카드 폭, 왼쪽 분석 칸은 최소 420px
  const place = () => {
    const w = card.current!.getBoundingClientRect();
    const b = box.current!;
    const ph = phone();
    // 폰에서 카드가 화면 아래쪽이면 상자는 화면 위에서 연다 (아래 공간이 좁으므로)
    const top = ph && w.top > innerHeight * 0.3 ? 16 : w.top;
    b.style.top = `${top}px`;
    b.style.right = `${innerWidth - w.right}px`;
    b.style.width = `${ph ? w.width : Math.min(Math.max(920, w.width + 460), innerWidth - 32)}px`;
    b.style.maxHeight = `${innerHeight - top - 16}px`;
    b.style.setProperty("--cw", `${w.width - 44}px`);
  };
  const cardClip = () =>
    clipOf(
      card.current!.getBoundingClientRect(),
      getComputedStyle(card.current!).borderTopLeftRadius,
    );
  const boxClip = () => clipOf(box.current!.getBoundingClientRect(), "30px");

  const type = () => {
    if (reduce()) return setShown(text.length);
    let i = 0;
    const tick = () => {
      i++;
      setShown(i);
      if (i < text.length) {
        const ch = text[i - 1];
        typing.current = window.setTimeout(tick, ch === "." ? 110 : ch === "\n" ? 50 : 9);
      }
    };
    tick();
  };

  const close = () => {
    const el = fx.current!;
    if (!el.classList.contains(styles.on) || el.classList.contains(styles.closing)) return;
    const clip = getComputedStyle(el).clipPath;
    const moved = pairs().map(([a, b, k]) => ({ a, b, k, start: getComputedStyle(b).transform }));
    const colors = colorPairs().map(([a, b, property]) => ({
      b,
      property,
      start: getComputedStyle(b)[property],
      end: getComputedStyle(a)[property],
    }));
    // 펼치는 도중 닫아도 현재 위치 · 색에서 출발한다.
    stopMotion();
    const d = reduce() ? 0 : 320;
    const o: KeyframeAnimationOptions = {
      duration: d,
      delay: d ? 70 : 0,
      easing: EASE_OUT,
      fill: "both",
    };
    clearTimeout(typing.current);
    el.classList.add(styles.closing);
    el.classList.remove(styles.ready);
    const ps = moved.map(({ a, b, k, start }) =>
      b.animate([{ transform: start }, { transform: from(a, b, k) }], o),
    );
    const cs = colors.map(({ b, property, start, end }) =>
      b.animate([{ [property]: start }, { [property]: end }], o),
    );
    const main = el.animate([{ clipPath: clip }, { clipPath: cardClip() }], o);
    motion.current = [main, ...ps, ...cs];
    main.finished
      .then(() => {
        document.documentElement.style.overflow = "";
        card.current?.classList.remove(styles.gone);
        el.classList.remove(styles.on, styles.closing);
        stopMotion();
        card.current?.focus();
      })
      .catch(() => {});
  };

  const open = () => {
    const el = fx.current!;
    if (el.classList.contains(styles.on)) return;
    stopMotion();
    document.documentElement.style.overflow = "hidden";
    const src = cardClip();
    place();
    setShown(0);
    el.classList.remove(styles.closing);
    el.classList.add(styles.on);
    box.current!.scrollTop = 0;
    const to = boxClip();
    const d = reduce() ? 0 : 560;
    const ps = pairs().map(([a, b, k]): [Element, string] => [b, from(a, b, k)]);
    el.style.clipPath = to;
    card.current!.classList.add(styles.gone);
    const main = el.animate([{ clipPath: src }, { clipPath: to }], { duration: d, easing: SPRING });
    const moves = ps.map(([b, t]) =>
      b.animate([{ transform: t }, { transform: "none" }], { duration: d, easing: SPRING }),
    );
    const colors = colorPairs().map(([a, b, property]) =>
      b.animate(
        [
          { [property]: getComputedStyle(a)[property] },
          { [property]: getComputedStyle(b)[property] },
        ],
        { duration: d, easing: "ease-in-out" },
      ),
    );
    motion.current = [main, ...moves, ...colors];
    main.finished.then(type).catch(() => {});
    ready.current = window.setTimeout(() => el.classList.add(styles.ready), reduce() ? 0 : 160);
    el.querySelector<HTMLButtonElement>(`.${styles.x}`)?.focus({ preventScroll: true });
  };

  // Esc · 창 크기 · 글꼴이 늦게 들어와 상자 높이가 바뀌면 잘라 낸 영역도 따라간다
  useEffect(() => {
    const el = fx.current;
    const b = box.current;
    if (!el || !b) return;
    const isOpen = () => el.classList.contains(styles.on) && !el.classList.contains(styles.closing);
    const sync = () => {
      if (isOpen() && !el.getAnimations().length) el.style.clipPath = boxClip();
    };
    const onResize = () => {
      if (!isOpen()) return;
      place();
      el.style.clipPath = boxClip();
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && isOpen() && close();
    const ro = new ResizeObserver(sync);
    ro.observe(b);
    addEventListener("resize", onResize);
    addEventListener("keydown", onKey);
    return () => {
      ro.disconnect();
      removeEventListener("resize", onResize);
      removeEventListener("keydown", onKey);
      clearTimeout(typing.current);
      stopMotion();
      document.documentElement.style.overflow = "";
    };
    // close · place 는 ref 만 읽는다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mounted, f]);

  const pct = f?.pct ?? null;
  if (failed) return <ForecastNotice mini={mini} failed onRetry={retry} />;
  if (!f) return <SalesForecastEmpty mini={mini} />;
  if (pct === null) return <ForecastNotice mini={mini} onRetry={retry} />;

  const sign = pct > 0 ? "+" : "";
  const typed = text.slice(0, shown);
  return (
    <>
      <button
        ref={card}
        type="button"
        className={cx(styles.card, mini && styles.mini)}
        style={vt("sales-card")}
        onClick={open}
        aria-haspopup="dialog"
      >
        <span ref={label} className={styles.lb}>
          <span style={vt("sales-lb")}>이번 주 매출 예측</span>
          {!mini && <Arrow />}
        </span>
        <span ref={num} className={`${styles.v} num`} style={vt("sales-num")}>
          {sign}
          {pct}
          <small>%</small>
        </span>
        {!mini && f.reason && (
          <span ref={why} className={styles.why} style={vt("sales-why")}>
            {f.reason}
          </span>
        )}
        <span
          ref={bars}
          className={styles.wk}
          style={{ gridTemplateColumns: `repeat(${Math.max(1, f.days.length)}, minmax(0, 1fr))` }}
        >
          {f.days.map((d, k) => (
            <span key={d.label} className={cx(k === hot && styles.hot)}>
              <i
                style={{
                  height: mini
                    ? `${(d.value / max) * 100}%`
                    : `${Math.round((d.value / max) * 60)}px`,
                  ...vt(`wk-${k}`),
                }}
              />
              {!mini && <em style={vt(`day-${k}`)}>{d.label}</em>}
            </span>
          ))}
        </span>
      </button>
      {mounted &&
        createPortal(
          <>
            <div className={styles.scrim} onClick={close} />
            <div
              ref={fx}
              className={styles.fx}
              role="dialog"
              aria-modal="true"
              aria-label="이번 주 매출 예측"
            >
              <div ref={box} className={styles.box}>
                <div className={styles.in}>
                  <div className={styles.r}>
                    <div ref={fxLabel} className={styles.top}>
                      이번 주 매출 예측
                    </div>
                    <h2 ref={fxNum} className="num">
                      {sign}
                      {pct}
                      <small>%</small>
                    </h2>
                    <div ref={fxWhy} className={cx(styles.sub, mini && styles.fade)}>
                      {f.reason}
                    </div>
                    <div className={cx(styles.indexNote, styles.fade)}>요일별 지수 · 평소 100</div>
                    <div
                      ref={fxBars}
                      className={styles.fwk}
                      style={{
                        gridTemplateColumns: `repeat(${Math.max(1, f.days.length)}, minmax(0, 1fr))`,
                      }}
                    >
                      {f.days.map((d, k) => (
                        <span key={d.label} className={cx(k === hot && styles.hot)}>
                          <b className={`${styles.fade} num`}>{fmt(d.value)}</b>
                          <i style={{ height: `${Math.round((d.value / max) * 184)}px` }} />
                          <em className={styles.fade}>{d.label}</em>
                        </span>
                      ))}
                    </div>
                    <div className={cx(styles.fac, styles.fade)}>
                      {f.factors.map((x, k) => (
                        <div key={x.name}>
                          <span className={styles.n}>
                            {x.name}
                            <small>{x.note}</small>
                          </span>
                          <span className={`${styles.fv} num`}>
                            {x.pct > 0 ? "+" : ""}
                            {x.pct}%
                          </span>
                          <span className={styles.bar}>
                            <i
                              style={{
                                width: `${pct ? Math.max(0, Math.min(100, (x.pct / pct) * 100)) : 0}%`,
                                transitionDelay: `${200 + k * 60}ms`,
                              }}
                            />
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                  <div className={styles.l}>
                    <div className={cx(styles.top, styles.fade)}>
                      <span className={styles.date}>{f.range}</span>
                      <button type="button" className={styles.x} onClick={close} aria-label="닫기">
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                          <path d="M6 6l12 12M18 6L6 18" />
                        </svg>
                      </button>
                    </div>
                    {/* 다 쳤을 때 높이를 미리 잡아 두어(투명한 원문) 치는 동안 아래가 밀리지 않는다 */}
                    {text && (
                      <div className={cx(styles.say, styles.fade)}>
                        <div className={styles.ghost}>{text}</div>
                        <div aria-hidden="true">
                          {typed}
                          {shown < text.length && <span className={styles.caret} />}
                        </div>
                      </div>
                    )}
                    <div className={cx(styles.note, styles.fade)}>
                      지그재고 AI 매출 예측은 틀릴 수 있습니다
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </>,
          document.body,
        )}
    </>
  );
}
