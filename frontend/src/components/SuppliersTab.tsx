"use client";

import { useState } from "react";
import { useSuppliers } from "@/hooks/useSuppliers";
import { CHANNEL_LABEL, DAY_LABEL } from "@/lib/suppliers";
import { Screen } from "./Screen";
import { Skeleton, SkTitle } from "./Skeleton";
import { SupplierModal } from "./SupplierModal";
import styles from "./SuppliersTab.module.css";

// 가게 그림 (로고 그림체: 빨간 선 · 흰 면 · 앞면 + 윗면 + 옆면). 240×224, 간판 글자는 HTML 로 얹는다
const AWN = Array.from({ length: 6 }, (_, k) => {
  const w = 182 / 6;
  const x = 14 + k * w;
  return { d: `M${x} 116h${w}v12a${w / 2} ${w / 2} 0 0 1-${w} 0z`, red: k % 2 === 0 };
});
function Shop() {
  return (
    <svg className={styles.art} viewBox="0 0 240 224" aria-hidden="true">
      <path d="M10 46L40 24H230L200 46Z" />
      <path d="M200 46L230 24V192L200 214Z" />
      <path d="M10 46H200V214H10Z" />
      <path className={styles.open} d="M200 66L230 44" />
      <rect x="30" y="62" width="150" height="42" rx="9" />
      {AWN.map((a) => (
        <path key={a.d} className={a.red ? styles.fill : undefined} d={a.d} />
      ))}
      <path className={styles.open} d="M10 116H200" />
      <rect x="24" y="152" width="84" height="46" rx="5" />
      <path className={`${styles.open} ${styles.thin}`} d="M40 160L32 170M56 160L40 182" />
      <path className={styles.open} d="M66 152V198" />
      <rect x="124" y="148" width="56" height="66" rx="5" />
      <circle className={styles.fill} cx="170" cy="184" r="3.5" />
    </svg>
  );
}

// 불러오는 동안: 가게 그림 자리 3칸
function Shops() {
  return (
    <div className={styles.street} aria-busy="true">
      {[0, 1, 2].map((k) => (
        <Skeleton key={k} w="100%" ratio="240 / 224" r="var(--r-card)" />
      ))}
    </div>
  );
}

export function SuppliersLoading() {
  return (
    <Screen title={<SkTitle w="3.2em" />}>
      <Shops />
    </Screen>
  );
}

// 거래처 탭 (시안 stock_v3.html, S15P31A308-270): 가게 그림, 누르면 모달. 마지막 칸은 거래처 추가
export function SuppliersTab() {
  const s = useSuppliers();
  // null = 닫힘, "" = 추가
  const [openId, setOpenId] = useState<string | null>(null);
  const suppliers = s.screen?.suppliers ?? [];
  const open = suppliers.find((x) => x.id === openId) ?? null;
  if (!s.screen && !s.error) return <SuppliersLoading />;
  return (
    <Screen title="거래처">
      {!s.screen ? (
        <p>{s.error}</p>
      ) : (
        <div className={styles.street}>
          {suppliers.map((x) => (
            <button
              key={x.id}
              type="button"
              className={styles.shop}
              onClick={() => setOpenId(x.id)}
            >
              <span className={styles.front}>
                <Shop />
                <span className={styles.sign}>{x.name}</span>
              </span>
              <span className={styles.meta}>
                {x.channel && <span>{CHANNEL_LABEL[x.channel]}</span>}
                {x.cutoff && <span>마감 {x.cutoff}</span>}
                {x.days.length > 0 && (
                  <span className={styles.days}>{x.days.map((d) => DAY_LABEL[d]).join(" ")}</span>
                )}
              </span>
            </button>
          ))}
          <button
            type="button"
            className={`${styles.shop} ${styles.add}`}
            onClick={() => setOpenId("")}
          >
            <span className={styles.front}>
              <Shop />
              <span className={styles.sign}>거래처 추가</span>
              <span className={styles.plus} aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M12 5v14M5 12h14" />
                </svg>
              </span>
            </span>
          </button>
        </div>
      )}
      {s.error && s.screen && <p role="alert">{s.error}</p>}
      {openId !== null && s.screen && (
        <SupplierModal
          key={openId}
          supplier={open}
          products={s.products}
          ingredients={s.screen.ingredients}
          onSave={(e) => (open ? s.editSupplier(open.id, e) : s.addSupplier(e))}
          onRemove={open ? () => s.removeSupplier(open.id) : undefined}
          onClose={() => setOpenId(null)}
        />
      )}
    </Screen>
  );
}
