"use client";

import {
  createContext,
  use,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
  type Ref,
} from "react";
import { useDragPlace } from "@/hooks/useDragPlace";
import { useSuppliers } from "@/hooks/useSuppliers";
import { fmt } from "@/lib/format";
import { PLACE_LABEL, colsFor, pageOf, pageWindow, type Place } from "@/lib/stockPlace";
import { fmtStock } from "@/lib/units";
import { packPriceLabel, safetyStock, shownUnit, supplierName } from "@/lib/suppliers";
import type { IngredientRow, Supplier } from "@/lib/types";
import {
  byCategory,
  catLines,
  keyOf,
  manyCategories,
  packLines,
  withHeads,
  type Line,
} from "@/lib/ingredientCategory";
import { CategoryIcon } from "./CategoryIcon";
import { IngredientModal } from "./IngredientModal";
import { IngredientSheet } from "./IngredientSheet";
import { Screen } from "./Screen";
import { Skeleton, SkTitle } from "./Skeleton";
import { useToast } from "./Toast";
import styles from "./IngredientsTab.module.css";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");
// CSS 와 같은 재료 칸 높이 · 카테고리 머리 · 줄 간격. 실제 여유 높이는 ResizeObserver로 잰다.
const ROW = 56;
// 그림 안 카테고리 머리 줄 높이 (--cat-head-h)
const HEAD = 24;
const GAP = 4;
// 재료명 16px 기준 최소 폭. 실제 높이는 viewport 계산을 유지한다.
const MIN = { fridge: 120, shelf: 128, shed: 128 };
const DESK = "(min-width: 761px)";
const DRAG = {
  over: styles.over,
  lift: styles.lift,
  ghost: styles.ghost,
  dragging: styles.dragging,
};

const avg = (a: number[]) => (a.length ? a.reduce((s, x) => s + x, 0) / a.length : 0);
const low = (r: IngredientRow) => r.stock !== null && r.stock < safetyStock(r.daily, r.safetyDays);
// 재고를 모르는 것 · 모자란 것을 앞에 (딱지 대신 순서)
const rank = (r: IngredientRow) => (r.stock === null ? 0 : low(r) ? 1 : 2);

const Chev = ({ left }: { left?: boolean }) => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d={left ? "M15 5l-7 7 7 7" : "M9 5l7 7-7 7"} />
  </svg>
);

// 그림 안 쪽 넘김: ‹ 1/2 ›
function Pager({ page, pages, go }: { page: number; pages: number; go: (p: number) => void }) {
  if (pages < 2) return null;
  return (
    <span className={styles.pg}>
      <button type="button" aria-label="이전" disabled={page === 0} onClick={() => go(page - 1)}>
        <Chev left />
      </button>
      <span className="num">
        {page + 1}/{pages}
      </span>
      <button
        type="button"
        aria-label="다음"
        disabled={page >= pages - 1}
        onClick={() => go(page + 1)}
      >
        <Chev />
      </button>
    </span>
  );
}

// 카테고리 아이콘 · 묶음 머리를 보일지 (manyCategories)
const ShowCats = createContext(true);

function Tile({
  r,
  drag,
  onOpen,
}: {
  r: IngredientRow;
  drag: boolean;
  onOpen: (id: string) => void;
}) {
  return (
    <button
      type="button"
      className={cx(
        styles.it,
        low(r) && styles.low,
        r.stock === null && styles.unk,
        drag && styles.grab,
      )}
      data-ing={drag ? r.id : undefined}
      data-place={r.place}
      onClick={() => onOpen(r.id)}
    >
      <b>{r.name}</b>
      <span className="num">
        {r.stock === null ? "–" : fmtStock(r.stock, r.baseUnit)}
        <small>{shownUnit(r.baseUnit)}</small>
      </span>
    </button>
  );
}

function Grid({
  cols,
  lines,
  none,
  drag,
  onOpen,
}: {
  cols: number;
  lines: Line<IngredientRow>[];
  none?: boolean;
  drag: boolean;
  onOpen: (id: string) => void;
}) {
  return (
    <div className={styles.rack} style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}>
      {none ? (
        <span className={styles.none}>없음</span>
      ) : (
        lines.flatMap((l) =>
          "head" in l
            ? [
                <span key={`c-${keyOf(l.head)}`} className={styles.ch}>
                  <CategoryIcon code={l.head.code} />
                  {l.head.name}
                </span>,
              ]
            : l.items.map((r) => <Tile key={r.id} r={r} drag={drag} onOpen={onOpen} />),
        )
      )}
    </div>
  );
}

// ── 로고 그림체 선 (시안 stock_v3.html): 글자 칸(.face)을 재서 둘레에 앞면 · 윗면 · 옆면 ──
type Seg = { d: string; open?: boolean; thin?: boolean; fill?: boolean };
type Pt = [number, number];
const P = (...p: Pt[]): Seg => ({ d: `M${p.map((x) => x.join(" ")).join("L")}Z` });
const L = (...p: Pt[]): Seg => ({ d: `M${p.map((x) => x.join(" ")).join("L")}`, open: true });
const rr = (x: number, y: number, w: number, h: number, r: number, fill = false): Seg => ({
  d: `M${x + r} ${y}h${w - 2 * r}a${r} ${r} 0 0 1 ${r} ${r}v${h - 2 * r}a${r} ${r} 0 0 1-${r} ${r}h${2 * r - w}a${r} ${r} 0 0 1-${r}-${r}v${2 * r - h}a${r} ${r} 0 0 1 ${r}-${r}z`,
  fill,
});
type Box = { x: number; y: number; w: number; h: number };
const off = (el: Element, root: Element): Box => {
  const a = el.getBoundingClientRect();
  const b = root.getBoundingClientRect();
  return { x: a.left - b.left, y: a.top - b.top, w: a.width, h: a.height };
};
const depth = () => (matchMedia(DESK).matches ? [26, 18] : [18, 13]);
const box = ({ x, y, w, h }: Box, dx: number, dy: number) => [
  P([x, y], [x + dx, y - dy], [x + w + dx, y - dy], [x + w, y]),
  P([x + w, y], [x + w + dx, y - dy], [x + w + dx, y + h - dy], [x + w, y + h]),
  P([x, y], [x + w, y], [x + w, y + h], [x, y + h]),
];

const DRAW: Record<"fridge" | "shelf" | "shed", (root: HTMLElement, f: Box) => Seg[]> = {
  fridge(root, f) {
    const [dx, dy] = depth();
    const s = box(f, dx, dy);
    const cs = [...root.querySelectorAll("[data-drop]")].map((c) => off(c, root));
    for (let i = 1; i < cs.length; i++) {
      const y = (cs[i - 1].y + cs[i - 1].h + cs[i].y) / 2;
      s.push(L([f.x, y], [f.x + f.w, y]), L([f.x + f.w, y], [f.x + f.w + dx, y - dy]));
    }
    // 문마다 손잡이, 아래 다리
    cs.forEach((c) =>
      s.push(L([f.x + f.w - 18, c.y + 18], [f.x + f.w - 18, c.y + 18 + Math.min(70, c.h * 0.45)])),
    );
    s.push(rr(f.x + 18, f.y + f.h, 22, 10, 3), rr(f.x + f.w - 40, f.y + f.h, 22, 10, 3));
    return s;
  },
  shelf(root, f) {
    const [dx, dy] = depth();
    const T = 12;
    const board = (y: number) => [
      P([f.x, y], [f.x + dx, y - dy], [f.x + f.w + dx, y - dy], [f.x + f.w, y]),
      P([f.x + f.w, y], [f.x + f.w + dx, y - dy], [f.x + f.w + dx, y - dy + T], [f.x + f.w, y + T]),
      P([f.x, y], [f.x + f.w, y], [f.x + f.w, y + T], [f.x, y + T]),
    ];
    const ps = [...root.querySelectorAll(`.${styles.plank}`)].map((p) => off(p, root));
    const s = [...board(f.y), ...ps.flatMap((p) => board(p.y + p.h))];
    const top = f.y + T;
    const last = ps.at(-1);
    const bot = last ? last.y + last.h + T + 16 : f.y + f.h;
    // 기둥: 앞 왼쪽 · 앞 오른쪽 + 옆면
    s.push(P([f.x, top], [f.x + 12, top], [f.x + 12, bot], [f.x, bot]));
    s.push(P([f.x + f.w - 12, top], [f.x + f.w, top], [f.x + f.w, bot], [f.x + f.w - 12, bot]));
    s.push(
      P([f.x + f.w, top], [f.x + f.w + dx, top - dy], [f.x + f.w + dx, bot - dy], [f.x + f.w, bot]),
    );
    return s;
  },
  shed(root, { x, y, w, h }) {
    const [dx, dy] = depth();
    const R = 62;
    const cy = y - R * 0.42;
    const s = [
      P([x, y], [x + w / 2, y - R], [x + w / 2 + dx, y - R - dy], [x + dx, y - dy]),
      P([x + w / 2, y - R], [x + w / 2 + dx, y - R - dy], [x + w + dx, y - dy], [x + w, y]),
      P([x + w, y], [x + w + dx, y - dy], [x + w + dx, y + h - dy], [x + w, y + h]),
      P([x, y], [x + w / 2, y - R], [x + w, y], [x + w, y + h], [x, y + h]),
      { d: `M${x + w / 2 - 9} ${cy}a9 9 0 1 0 18 0a9 9 0 1 0-18 0` },
      { ...L([x + w / 2 - 9, cy], [x + w / 2 + 9, cy]), thin: true },
      { ...L([x + w / 2, cy - 9], [x + w / 2, cy + 9]), thin: true },
    ];
    // 셔터: 위 34px 는 반쯤 내린 셔터
    const bay = root.querySelector("[data-drop]");
    if (bay) {
      const b = off(bay, root);
      s.push(rr(b.x, b.y, b.w, b.h, 6), rr(b.x, b.y, b.w, 34, 6));
      for (const k of [11, 22])
        s.push({ ...L([b.x + 6, b.y + k], [b.x + b.w - 6, b.y + k]), thin: true });
      s.push(rr(b.x + b.w / 2 - 12, b.y + 26, 24, 5, 2.5, true));
    }
    return s;
  },
};

function Art({
  kind,
  faceRef,
  faceStyle,
  drop,
  children,
}: {
  kind: keyof typeof DRAW;
  faceRef?: Ref<HTMLDivElement>;
  faceStyle?: CSSProperties;
  drop?: Place;
  children: ReactNode;
}) {
  const root = useRef<HTMLDivElement | null>(null);
  const [segs, setSegs] = useState<Seg[]>([]);
  // 칸 크기가 바뀔 때마다 다시 그린다. 같은 선이면 그대로 둔다
  useLayoutEffect(() => {
    const el = root.current;
    if (!el) return;
    const run = () => {
      const face = el.querySelector(`.${styles.face}`);
      if (!face) return;
      const next = DRAW[kind](el, off(face, el));
      setSegs((prev) => (JSON.stringify(prev) === JSON.stringify(next) ? prev : next));
    };
    run();
    const ro = new ResizeObserver(run);
    ro.observe(el);
    el.querySelectorAll("[data-drop], ." + styles.plank).forEach((c) => ro.observe(c));
    return () => ro.disconnect();
  });
  return (
    <div ref={root} className={cx(styles.obj, styles[kind])}>
      <svg className={styles.ln} aria-hidden="true">
        {segs.map((s, i) => (
          <path
            key={i}
            d={s.d}
            className={cx(s.open && styles.open, s.thin && styles.thin, s.fill && styles.fill)}
          />
        ))}
      </svg>
      <div className={styles.face} ref={faceRef} style={faceStyle} data-drop={drop}>
        {children}
      </div>
    </div>
  );
}

type Fit = {
  fridge: number;
  shelf: number;
  shed: number;
  stacked: boolean;
  room: Record<Place, number[]> | null;
};

// 냉장고 · 선반 · 창고. 데스크톱은 배정된 각 rack 높이로, 폰은 기존 줄 수로 쪽을 나눈다.
function Fixtures({
  rows,
  drag,
  onOpen,
  onMove,
}: {
  rows: IngredientRow[];
  drag: boolean;
  onOpen: (id: string) => void;
  onMove: (id: string, place: Place) => void;
}) {
  const [page, setPage] = useState<Record<Place, number>>({
    FREEZER: 0,
    FRIDGE: 0,
    SHELF: 0,
    WAREHOUSE: 0,
  });
  const heads = use(ShowCats);
  const headU = heads ? (HEAD + GAP) / (ROW + GAP) : 0;
  const [fit, setFit] = useState<Fit>({ fridge: 2, shelf: 3, shed: 3, stacked: false, room: null });
  const storeRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const store = storeRef.current!;
    const measure = () => {
      const inner = (sel: string) => {
        const el = store.querySelector<HTMLElement>(sel);
        if (!el) return 0;
        const cs = getComputedStyle(el);
        return el.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
      };
      const capacity = (sel: string) => {
        const el = store.querySelector<HTMLElement>(sel);
        return Math.max(1 + headU, ((el?.clientHeight ?? 0) + GAP) / (ROW + GAP));
      };
      const desktop = matchMedia(DESK).matches;
      const next: Fit = {
        fridge: colsFor(inner('[data-drop="FREEZER"]'), MIN.fridge, GAP),
        shelf: colsFor(inner(`.${styles.plank}`), MIN.shelf, GAP),
        shed: colsFor(inner('[data-drop="WAREHOUSE"]'), MIN.shed, GAP),
        stacked: desktop && store.clientHeight >= 700,
        room: desktop
          ? {
              FREEZER: [capacity(`[data-drop="FREEZER"] .${styles.rack}`)],
              FRIDGE: [capacity(`[data-drop="FRIDGE"] .${styles.rack}`)],
              SHELF: [
                capacity(`.${styles.plank}:first-child .${styles.rack}`),
                capacity(`.${styles.plank}:last-child .${styles.rack}`),
              ],
              WAREHOUSE: [capacity(`[data-drop="WAREHOUSE"] .${styles.rack}`)],
            }
          : null,
      };
      setFit((f) => (JSON.stringify(f) === JSON.stringify(next) ? f : next));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(store);
    store.querySelectorAll(`.${styles.rack}`).forEach((el) => ro.observe(el));
    return () => ro.disconnect();
  }, [headU]);

  const cols: Record<Place, number> = {
    FREEZER: fit.fridge,
    FRIDGE: fit.fridge,
    SHELF: fit.shelf,
    WAREHOUSE: fit.shed,
  };
  const line = 1 + headU;
  const room = fit.room ?? {
    FREEZER: [2 * line],
    FRIDGE: [4 * line],
    SHELF: [line, line],
    WAREHOUSE: [2 * line],
  };
  const all = (p: Place) => rows.filter((r) => r.place === p);
  const paged = (p: Place, list = all(p)) =>
    packLines(catLines(list, cols[p], heads), room[p], headU);
  const cut = (p: Place) => {
    const pages = paged(p);
    const at = Math.min(Math.max(0, page[p]), pages.length - 1);
    return { areas: pages[at], page: at, pages: pages.length };
  };
  const go = (p: Place) => (n: number) => setPage({ ...page, [p]: n });
  const frz = cut("FREEZER");
  const chl = cut("FRIDGE");
  const shelf = cut("SHELF");
  const shed = cut("WAREHOUSE");

  useDragPlace(drag, DRAG, (id, place) => {
    // 옮긴 재료가 보이는 쪽으로
    const moved = rows
      .map((r) => (r.id === id ? { ...r, place } : r))
      .filter((r) => r.place === place);
    const at = paged(place, moved).findIndex((pg) =>
      pg.some((a) => a.some((l) => "items" in l && l.items.some((r) => r.id === id))),
    );
    setPage((p) => ({ ...p, [place]: Math.max(0, at) }));
    onMove(id, place);
  });

  const head = (title: string, n: number, pager?: ReactNode) => (
    <div className={styles.fxName}>
      <h2>{title}</h2>
      <span className="num">{n}</span>
      {pager}
    </div>
  );
  const cmp = (p: Place, c: ReturnType<typeof cut>) => (
    <div className={styles.cmp} data-drop={p}>
      <span className={styles.cl}>{PLACE_LABEL[p]}</span>
      <span className={styles.cmpPg}>
        <Pager page={c.page} pages={c.pages} go={go(p)} />
      </span>
      <Grid
        cols={fit.fridge}
        lines={c.areas[0]}
        none={!all(p).length}
        drag={drag}
        onOpen={onOpen}
      />
    </div>
  );

  return (
    <div
      className={styles.store}
      ref={storeRef}
      data-cats={heads || undefined}
      data-layout={fit.stacked ? "stacked" : "side"}
    >
      <div className={styles.fixture}>
        {head("냉장고", all("FREEZER").length + all("FRIDGE").length)}
        <Art kind="fridge">
          {cmp("FREEZER", frz)}
          {cmp("FRIDGE", chl)}
        </Art>
      </div>
      <div className={styles.right}>
        <div className={styles.fixture}>
          {head(
            "선반",
            all("SHELF").length,
            <Pager page={shelf.page} pages={shelf.pages} go={go("SHELF")} />,
          )}
          <Art kind="shelf" drop="SHELF">
            {[0, 1].map((k) => (
              <div key={k} className={styles.plank}>
                <Grid
                  cols={fit.shelf}
                  lines={shelf.areas[k]}
                  none={k === 0 && !all("SHELF").length}
                  drag={drag}
                  onOpen={onOpen}
                />
              </div>
            ))}
          </Art>
        </div>
        <div className={styles.fixture}>
          {head(
            "창고",
            all("WAREHOUSE").length,
            <Pager page={shed.page} pages={shed.pages} go={go("WAREHOUSE")} />,
          )}
          <Art kind="shed">
            <div className={styles.bay} data-drop="WAREHOUSE">
              <Grid
                cols={fit.shed}
                lines={shed.areas[0]}
                none={!all("WAREHOUSE").length}
                drag={drag}
                onOpen={onOpen}
              />
            </div>
          </Art>
        </div>
      </div>
    </div>
  );
}

// 표: 데스크톱은 실제 가용 높이, 폰은 기존 10개씩. 번호 쪽 넘김 ‹ 1 2 3 4 5 ›
const TBL = 10;
function Table({
  rows,
  suppliers,
  onOpen,
}: {
  rows: IngredientRow[];
  suppliers: Supplier[];
  onOpen: (id: string) => void;
}) {
  const [want, setWant] = useState(0);
  const heads = use(ShowCats);
  const root = useRef<HTMLDivElement>(null);
  const [room, setRoom] = useState<{ rows: number; head: number } | null>(null);
  useLayoutEffect(() => {
    const el = root.current!;
    let measuredWidth = 0;
    let measuredHeight = 0;
    let rowCeiling = 0;
    let headCeiling = 0;
    const measure = () => {
      if (!matchMedia(DESK).matches) return setRoom(null);
      if (measuredWidth !== el.clientWidth) {
        rowCeiling = 0;
        headCeiling = 0;
      }
      if (measuredWidth && (measuredWidth !== el.clientWidth || measuredHeight !== el.clientHeight))
        setWant(0);
      measuredWidth = el.clientWidth;
      measuredHeight = el.clientHeight;
      // 다음 쪽의 긴 셀이 나타나도 용량이 다시 늘어 재료를 건너뛰지 않게 한다.
      rowCeiling = Math.max(
        rowCeiling,
        ...[...el.querySelectorAll(`.${styles.row}`)].map(
          (row) => row.getBoundingClientRect().height,
        ),
        53,
      );
      headCeiling = Math.max(
        headCeiling,
        ...[...el.querySelectorAll(`.${styles.grp}`)].map(
          (row) => row.getBoundingClientRect().height,
        ),
        46,
      );
      const row = rowCeiling;
      const head = headCeiling;
      const cs = getComputedStyle(el);
      const height =
        el.clientHeight -
        parseFloat(cs.paddingTop) -
        parseFloat(cs.paddingBottom) -
        (el.querySelector("thead")?.getBoundingClientRect().height ?? 44) -
        1;
      const next = {
        rows: Math.max(1 + (heads ? head / row : 0), height / row),
        head: heads ? head / row : 0,
      };
      setRoom((prev) => (JSON.stringify(prev) === JSON.stringify(next) ? prev : next));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    if (el.querySelector("table")) ro.observe(el.querySelector("table")!);
    return () => ro.disconnect();
  }, [heads]);
  const packed = room ? packLines(catLines(rows, 1, heads), [room.rows], room.head) : null;
  const pages = packed?.length ?? pageOf(rows, TBL, want).pages;
  const page = Math.min(Math.max(0, want), pages - 1);
  const cut = packed
    ? packed[page][0].flatMap((l) => ("items" in l ? l.items : []))
    : pageOf(rows, TBL, page).items;
  const items = heads ? withHeads(cut, rows) : cut;
  return (
    <>
      <div className={styles.tbl} ref={root}>
        <table>
          <thead>
            <tr>
              <th>재료</th>
              <th>보관</th>
              <th className={styles.r}>추정 재고</th>
              <th className={styles.r}>안전재고</th>
              <th className={styles.r}>하루 사용</th>
              <th>거래처</th>
              <th className={styles.r}>단가</th>
            </tr>
          </thead>
          <tbody>
            {items.length ? (
              items.map((r) => {
                if ("head" in r)
                  return (
                    <tr key={`c-${keyOf(r.head)}`} className={styles.grp}>
                      <th colSpan={7}>
                        <CategoryIcon code={r.head.code} />
                        <b>{r.head.name}</b>
                        <span className="num">{r.n}</span>
                      </th>
                    </tr>
                  );
                const u = shownUnit(r.baseUnit);
                return (
                  <tr
                    key={r.id}
                    className={cx(styles.row, low(r) && styles.low)}
                    tabIndex={0}
                    onClick={() => onOpen(r.id)}
                    onKeyDown={(e) => e.key === "Enter" && onOpen(r.id)}
                  >
                    <td>
                      <b>{r.name}</b>
                    </td>
                    <td>
                      <span className={styles.loc}>{PLACE_LABEL[r.place]}</span>
                    </td>
                    <td className={cx(styles.r, styles.q, "num")}>
                      {r.stock === null ? "–" : fmtStock(r.stock, r.baseUnit)} <small>{u}</small>
                    </td>
                    <td className={cx(styles.r, "num")}>
                      {fmt(safetyStock(r.daily, r.safetyDays))} <small>{u}</small>
                    </td>
                    <td className={cx(styles.r, "num")}>
                      {fmt(avg(r.daily))} <small>{u}</small>
                    </td>
                    <td>{supplierName(suppliers, r.supplierId)}</td>
                    <td className={cx(styles.r, "num")}>{packPriceLabel(r)}</td>
                  </tr>
                );
              })
            ) : (
              <tr>
                <td colSpan={7} className={styles.none}>
                  없음
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {pages > 1 && (
        <nav className={styles.pn} aria-label="쪽">
          <button
            type="button"
            aria-label="이전"
            disabled={page === 0}
            onClick={() => setWant(page - 1)}
          >
            <Chev left />
          </button>
          {pageWindow(page, pages).map((k) => (
            <button
              key={k}
              type="button"
              className="num"
              aria-current={k === page ? "page" : undefined}
              onClick={() => setWant(k)}
            >
              {k + 1}
            </button>
          ))}
          <button
            type="button"
            aria-label="다음"
            disabled={page >= pages - 1}
            onClick={() => setWant(page + 1)}
          >
            <Chev />
          </button>
        </nav>
      )}
    </>
  );
}

// 불러오는 동안 (AppGate · 재료 전): 냉장고 | 선반 · 창고 자리. 글자 없이 막대만
export function IngredientsLoading() {
  return (
    <Screen title={<SkTitle w="2.4em" />} fitViewport>
      <div className={styles.store} aria-busy="true" data-loading>
        <Skeleton w="100%" h="var(--stock-loading-h, 520px)" r="var(--r-card)" />
        <div className={styles.right}>
          <Skeleton w="100%" h="var(--stock-loading-h, 240px)" r="var(--r-card)" />
          <Skeleton w="100%" h="var(--stock-loading-h, 280px)" r="var(--r-card)" />
        </div>
      </div>
    </Screen>
  );
}

// 재료 탭 (시안 stock_v3.html, S15P31A308-270): 그림/표 전환 · 검색 · 끌어서 보관 위치 바꾸기 · 상세 모달
export function IngredientsTab() {
  const s = useSuppliers();
  const [mode, setMode] = useState<"pic" | "tbl">("pic");
  const [q, setQ] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);
  const [editId, setEditId] = useState<string | null>(null);
  const { toast, show } = useToast();
  const ings = s.screen?.ingredients ?? [];
  const query = q.replace(/\s/g, "");
  const rows = ings
    .filter((r) => !query || r.name.replace(/\s/g, "").includes(query))
    .sort((a, b) => byCategory(a, b) || rank(a) - rank(b));
  // 보관 위치를 서버가 줄 때만 끌 수 있다 (S15P31A308-268 반영 전에는 옮겨도 저장되지 않으므로)
  const drag = ings.some((r) => r.placeSaved);
  const open = ings.find((r) => r.id === openId) ?? null;
  const editing = ings.find((r) => r.id === editId) ?? null;

  const aside = (
    <>
      <div className={styles.seg} role="group" aria-label="보기">
        {(["pic", "tbl"] as const).map((m) => (
          <button key={m} type="button" aria-pressed={mode === m} onClick={() => setMode(m)}>
            {m === "pic" ? "그림" : "표"}
          </button>
        ))}
      </div>
      <label className={styles.find}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="M20 20l-4-4" />
        </svg>
        <input
          type="search"
          value={q}
          placeholder="재료 찾기"
          aria-label="재료 찾기"
          onChange={(e) => setQ(e.target.value)}
        />
      </label>
    </>
  );

  if (!s.screen && !s.error) return <IngredientsLoading />;
  return (
    <ShowCats value={manyCategories(ings)}>
      <Screen title="재료" aside={aside} fitViewport>
        {!s.screen ? (
          <p>{s.error}</p>
        ) : mode === "pic" ? (
          <Fixtures
            key={query}
            rows={rows}
            drag={drag}
            onOpen={setOpenId}
            onMove={async (id, place) => {
              if (!(await s.moveIngredient(id, place)))
                show(`${ings.find((r) => r.id === id)?.name ?? "재료"}을(를) 옮기지 못했어요`);
            }}
          />
        ) : (
          <Table key={query} rows={rows} suppliers={s.screen.suppliers} onOpen={setOpenId} />
        )}
        {open && s.screen && (
          <IngredientModal
            key={open.id}
            row={open}
            suppliers={s.screen.suppliers}
            onCount={s.countStock}
            onEdit={() => {
              setOpenId(null);
              setEditId(open.id);
            }}
            onClose={() => setOpenId(null)}
          />
        )}
        {editing && s.screen && (
          <IngredientSheet
            key={editing.id}
            ing={editing}
            suppliers={s.screen.suppliers}
            onSave={s.editIngredient}
            onClose={() => setEditId(null)}
          />
        )}
        {toast}
      </Screen>
    </ShowCats>
  );
}
